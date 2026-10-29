package pl.aiwcorpo.mcp.platform.policy;

import com.cedarpolicy.BasicAuthorizationEngine;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import javax.net.ssl.SSLContext;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.policy.bundle.BundleVerifier;
import java.net.URI;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.aiwcorpo.mcp.cedar.entities.ResultFilter;
import pl.aiwcorpo.mcp.policy.bundle.BundlePointer;
import pl.aiwcorpo.mcp.policy.bundle.BundleRejectedException;
import pl.aiwcorpo.mcp.policy.bundle.Channel;
import pl.aiwcorpo.mcp.policy.bundle.PolicyBundle;
import pl.aiwcorpo.mcp.policy.bundle.PolicyStore;
import pl.aiwcorpo.mcp.policy.bundle.PolicyStore.Snapshot;
import pl.aiwcorpo.mcp.policy.bundle.Revisions;

/**
 * The policy a server decides with: the same signed bundle (C7) the gateway loads, pulled from the repository, verified
 * and swapped atomically by {@link PolicyStore} of mcp-policy-bundle (signature, strictly increasing revision with
 * rollback protection across restarts, schema equal to the contracts', policies validated against it).
 *
 * <p>Fail-closed: a server does not start without a main bundle ({@link #start}); a bundle that is rejected (bad
 * signature, older revision, broken content) leaves the previous one active and is counted and logged; after a restart
 * in which the emergency overrides bundle was active, {@link #snapshot()} refuses until that bundle is loaded again or
 * {@code overrides-grace} has passed (then the server serves and logs an alarm).
 *
 * <p>The main bundle is pulled every 30 s and the overrides bundle every 5 s unless configured otherwise. A caller whose
 * token names a policy revision {@code prv} newer than the loaded one makes the engine pull the main bundle at once (at
 * most every two seconds), so a server that lags behind the gateway does not refuse calls the gateway has just allowed.
 */
public final class PolicyEngine implements AutoCloseable {

    /** Defaults of the pull intervals (C7). */
    public static final Duration MAIN_INTERVAL = Duration.ofSeconds(30);
    public static final Duration OVERRIDES_INTERVAL = Duration.ofSeconds(5);

    private static final Logger LOG = LoggerFactory.getLogger(PolicyEngine.class);
    private static final String ZERO_REVISION = "00000000000000-0000000";
    private static final long HINT_PULL_MIN_NANOS = TimeUnit.SECONDS.toNanos(2);

    private record Source(URI latest, Duration interval) {
    }

    private static final class ChannelState {
        String etag;
        String rejectedRevision;
    }

    private final PolicyStore store;
    private final PolicyBundleClient client;
    private final Map<Channel, Source> sources = new EnumMap<>(Channel.class);
    private final Map<Channel, ChannelState> states = new EnumMap<>(Channel.class);
    /** One pull of a channel at a time, and a stuck channel does not hold up the other. */
    private final Map<Channel, ReentrantLock> locks = new EnumMap<>(Channel.class);
    private final Duration startupTimeout;
    private final Duration overridesGrace;
    private final MeterRegistry meters;
    private volatile long startedNanos = System.nanoTime();
    private final AtomicLong lastHintPull = new AtomicLong(System.nanoTime() - HINT_PULL_MIN_NANOS);
    private final AtomicBoolean graceAlarmRaised = new AtomicBoolean();
    private ScheduledExecutorService scheduler;

    /**
     * @param main      where the main bundle is pulled from and how often
     * @param overrides the emergency bundle, or {@code null} when none is configured
     */
    public PolicyEngine(PolicyStore store, PolicyBundleClient client, URI main, Duration mainInterval, URI overrides,
                        Duration overridesInterval, Duration startupTimeout, Duration overridesGrace, MeterRegistry meters) {
        this.store = store;
        this.client = client;
        sources.put(Channel.MAIN, new Source(main, mainInterval));
        if (overrides != null) {
            sources.put(Channel.OVERRIDES, new Source(overrides, overridesInterval));
        }
        states.put(Channel.MAIN, new ChannelState());
        states.put(Channel.OVERRIDES, new ChannelState());
        locks.put(Channel.MAIN, new ReentrantLock());
        locks.put(Channel.OVERRIDES, new ReentrantLock());
        this.startupTimeout = startupTimeout;
        this.overridesGrace = overridesGrace;
        this.meters = meters;
    }

    /**
     * The engine for the configured channels, started: the first main bundle is loaded (and the overrides bundle, when one
     * is configured) or the call throws.
     */
    public static PolicyEngine create(McpPlatformProperties.Policy policy, SSLContext sslContext, MeterRegistry meters)
            throws IOException, GeneralSecurityException {
        return create(policy, sslContext, meters, false);
    }

    /** @param devstackProfile the server runs with the {@code devstack} profile: with {@code allow-test-keys} a {@code dev-} key id is accepted (CC-137) */
    public static PolicyEngine create(McpPlatformProperties.Policy policy, SSLContext sslContext, MeterRegistry meters,
                                      boolean devstackProfile) throws IOException, GeneralSecurityException {
        BundleVerifier main = verifier(Channel.MAIN, policy.getMain(), policy.isAllowTestKeys(), devstackProfile);
        boolean withOverrides = policy.getOverrides().getUrl() != null && !policy.getOverrides().getUrl().isBlank();
        BundleVerifier overrides = withOverrides ? verifier(Channel.OVERRIDES, policy.getOverrides(), policy.isAllowTestKeys(), devstackProfile)
                : main;
        Map<Channel, String> minimum = new EnumMap<>(Channel.class);
        if (policy.getMain().getMinimumRevision() != null) {
            minimum.put(Channel.MAIN, policy.getMain().getMinimumRevision());
        }
        if (withOverrides && policy.getOverrides().getMinimumRevision() != null) {
            minimum.put(Channel.OVERRIDES, policy.getOverrides().getMinimumRevision());
        }
        PolicyStore store = policy.getStateDir() == null || policy.getStateDir().isBlank()
                ? new PolicyStore(main, overrides)
                : new PolicyStore(main, overrides, new PolicyStore.Persistence(Path.of(policy.getStateDir()), minimum,
                        warning -> LOG.warn("event=policy_state_warning detail=\"{}\"", warning)));
        PolicyEngine engine = new PolicyEngine(store,
                new PolicyBundleClient(sslContext, policy.getConnectTimeout(), policy.getReadTimeout()),
                URI.create(policy.getMain().getUrl()),
                policy.getMain().getRefreshInterval() == null ? MAIN_INTERVAL : policy.getMain().getRefreshInterval(),
                withOverrides ? URI.create(policy.getOverrides().getUrl()) : null,
                policy.getOverrides().getRefreshInterval() == null ? OVERRIDES_INTERVAL
                        : policy.getOverrides().getRefreshInterval(),
                policy.getStartupTimeout(), policy.getOverridesGrace(), meters);
        engine.start();
        return engine;
    }

    private static BundleVerifier verifier(Channel channel, McpPlatformProperties.PolicyChannel config, boolean allowTestKeys,
                                           boolean devstackProfile) throws IOException, GeneralSecurityException {
        String pem = config.getPublicKey() != null && !config.getPublicKey().isBlank() ? config.getPublicKey()
                : Files.readString(Path.of(config.getPublicKeyFile()));
        PublicKey key = BundleVerifier.ed25519PublicKeyFromPem(pem);
        return BundleVerifier.of(channel, key, config.getKeyId(), allowTestKeys).withDevstackProfile(devstackProfile);
    }

    /**
     * Loads the first bundles and starts the periodic pulls.
     *
     * @throws IllegalStateException when no main bundle could be loaded within the startup timeout
     */
    public void start() {
        long deadline = System.nanoTime() + startupTimeout.toNanos();
        while (!store.current().isPresent()) {
            refresh(Channel.MAIN);
            if (store.current().isPresent()) {
                break;
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("No policy bundle could be loaded from " + sources.get(Channel.MAIN).latest()
                        + " within " + startupTimeout.toSeconds() + " s (a server without a signed policy does not start; "
                        + "see the log for event=policy_bundle_rejected or event=policy_bundle_fetch_failed)");
            }
            sleep(500);
        }
        if (sources.containsKey(Channel.OVERRIDES)) {
            while (store.current().flatMap(Snapshot::overrides).isEmpty() && System.nanoTime() < deadline) {
                refresh(Channel.OVERRIDES);
                if (store.current().flatMap(Snapshot::overrides).isPresent() || !store.awaitingOverrides()
                        && states.get(Channel.OVERRIDES).etag != null) {
                    break;
                }
                sleep(500);
            }
        }
        startedNanos = System.nanoTime();
        scheduler = Executors.newScheduledThreadPool(2, task -> {
            Thread thread = new Thread(task, "policy-bundle-pull");
            thread.setDaemon(true);
            return thread;
        });
        sources.forEach((channel, source) -> scheduler.scheduleWithFixedDelay(() -> refresh(channel),
                source.interval().toMillis(), source.interval().toMillis(), TimeUnit.MILLISECONDS));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the policy bundle", ex);
        }
    }

    /** One pull of a channel; never throws: a failure leaves the active bundle in place. */
    public void refresh(Channel channel) {
        ReentrantLock lock = locks.get(channel);
        lock.lock();
        try {
            pull(channel);
        } finally {
            lock.unlock();
        }
    }

    private void pull(Channel channel) {
        Source source = sources.get(channel);
        if (source == null) {
            return;
        }
        ChannelState state = states.get(channel);
        try {
            Optional<PolicyBundleClient.Pointer> pointer = client.pointer(source.latest(), state.etag);
            if (pointer.isEmpty()) {
                return;
            }
            BundlePointer latest = pointer.get().pointer();
            if (latest.revision().equals(loadedRevision(channel).orElse(null))) {
                state.etag = pointer.get().etag();
                return;
            }
            if (latest.revision().equals(state.rejectedRevision)) {
                return;
            }
            PolicyBundleClient.Bundle bundle = client.download(source.latest(), latest);
            try {
                Snapshot snapshot = channel == Channel.MAIN
                        ? store.loadMain(latest, bundle.archive(), bundle.signature())
                        : store.loadOverrides(latest, bundle.archive(), bundle.signature());
                state.etag = pointer.get().etag();
                state.rejectedRevision = null;
                PolicyBundle loaded = channel == Channel.MAIN ? snapshot.main() : snapshot.overrides().orElseThrow();
                LOG.info("event=policy_bundle_loaded channel={} revision={}", channel.name().toLowerCase(java.util.Locale.ROOT),
                        loaded.revision());
            } catch (BundleRejectedException ex) {
                state.rejectedRevision = latest.revision();
                state.etag = pointer.get().etag();
                count("mcp_server_policy_bundle_rejected_total", channel, ex.reason().wireCode());
                LOG.warn("event=policy_bundle_rejected channel={} revision={} reason={} detail=\"previous bundle stays active\"",
                        channel.name().toLowerCase(java.util.Locale.ROOT), latest.revision(), ex.reason().wireCode());
            }
        } catch (BundleRejectedException ex) {
            count("mcp_server_policy_bundle_rejected_total", channel, ex.reason().wireCode());
            LOG.warn("event=policy_bundle_rejected channel={} reason={} detail=\"latest.json; previous bundle stays active\"",
                    channel.name().toLowerCase(java.util.Locale.ROOT), ex.reason().wireCode());
        } catch (IOException ex) {
            count("mcp_server_policy_bundle_fetch_failed_total", channel, "io");
            LOG.warn("event=policy_bundle_fetch_failed channel={} detail=\"{}\"",
                    channel.name().toLowerCase(java.util.Locale.ROOT), ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            count("mcp_server_policy_bundle_fetch_failed_total", channel, "error");
            LOG.error("event=policy_bundle_fetch_failed channel={} detail=\"{}\"",
                    channel.name().toLowerCase(java.util.Locale.ROOT), ex.getClass().getName());
        }
    }

    private Optional<String> loadedRevision(Channel channel) {
        return store.current().flatMap(snapshot -> channel == Channel.MAIN ? Optional.of(snapshot.main().revision())
                : snapshot.overrides().map(PolicyBundle::revision));
    }

    private void count(String name, Channel channel, String reason) {
        if (meters != null) {
            meters.counter(name, "channel", channel.name().toLowerCase(java.util.Locale.ROOT), "reason", reason).increment();
        }
    }

    /**
     * The policy to decide with.
     *
     * @throws PolicyUnavailableException when there is none yet, or the overrides bundle is awaited
     */
    public Snapshot snapshot() {
        Snapshot current = store.current().orElseThrow(() -> new PolicyUnavailableException("no policy bundle is loaded"));
        if (store.awaitingOverrides()) {
            if (System.nanoTime() - startedNanos < overridesGrace.toNanos()) {
                throw new PolicyUnavailableException("the emergency overrides bundle that was active before the restart has not been loaded again");
            }
            if (graceAlarmRaised.compareAndSet(false, true)) {
                LOG.error("event=policy_overrides_missing_alarm detail=\"serving without the overrides bundle that was active before the restart\"");
            }
        }
        return current;
    }

    /** Revision of the main bundle in use, empty before the first load. */
    public Optional<String> mainRevision() {
        return loadedRevision(Channel.MAIN);
    }

    /** The Cedar authorizer over {@code snapshot}: only a clean allow counts, see {@link ResultFilter#cedarAuthorizer}. */
    public ResultFilter.Authorizer authorizer(Snapshot snapshot) {
        return ResultFilter.cedarAuthorizer(new BasicAuthorizationEngine(), snapshot.policies(), snapshot.schema());
    }

    /** Id prefix of the forbid rules that narrow a call to the virtual server link ({@code vs-narrow-call-*} and {@code vs-narrow-*}, cedar/examples/virtual-server.cedar). */
    public static final String LINK_NARROWING_PREFIX = "vs-narrow-";

    /**
     * An authorizer for one call that remembers whether its last denial came only from the narrowing of the virtual server link: every
     * determining policy is a {@value #LINK_NARROWING_PREFIX} rule. The server then answers as for a resource that does not exist
     * ({@code not_found_or_denied}) and audits {@code link_scope} (CC-146c). Not thread safe: one per call.
     */
    public static final class RecordingAuthorizer implements ResultFilter.Authorizer {

        private final com.cedarpolicy.BasicAuthorizationEngine cedar = new BasicAuthorizationEngine();
        private final Snapshot snapshot;
        private boolean lastDeniedByLink;

        RecordingAuthorizer(Snapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public boolean isAllowed(pl.aiwcorpo.mcp.cedar.entities.CedarRequest request) throws Exception {
            lastDeniedByLink = false;
            com.cedarpolicy.model.AuthorizationResponse response = cedar.isAuthorized(request.toAuthorizationRequest(snapshot.schema()),
                    snapshot.policies(), request.cedarEntities());
            if (response.success.isEmpty()) {
                return false;
            }
            com.cedarpolicy.model.AuthorizationSuccessResponse success = response.success.get();
            if (success.isAllowed() && success.getErrors().isEmpty()) {
                return true;
            }
            lastDeniedByLink = !success.isAllowed() && !success.getReason().isEmpty()
                    && success.getReason().stream().allMatch(id -> id.startsWith(LINK_NARROWING_PREFIX));
            return false;
        }

        /** True when the last request was denied and every determining policy narrows to the link. */
        public boolean lastDeniedByLinkNarrowing() {
            return lastDeniedByLink;
        }
    }

    /** As {@link #authorizer}, remembering why the last request was denied. */
    public RecordingAuthorizer recordingAuthorizer(Snapshot snapshot) {
        return new RecordingAuthorizer(snapshot);
    }

    /**
     * Asks for a pull of the main bundle when the caller's token names a newer revision than the loaded one (at most every
     * two seconds), and returns at once: the pull runs on a thread of the engine, so the call that carries the hint is decided
     * with the bundle loaded now. The all-zero revision of a gateway without a bundle never triggers it.
     */
    public void hintRevision(String policyRevision) {
        if (policyRevision == null || ZERO_REVISION.equals(policyRevision) || !Revisions.isValid(policyRevision)) {
            return;
        }
        Optional<String> loaded = mainRevision();
        if (loaded.isPresent() && !Revisions.isNewer(policyRevision, loaded.get())) {
            return;
        }
        long now = System.nanoTime();
        long last = lastHintPull.get();
        ScheduledExecutorService pool = scheduler;
        if (pool != null && now - last >= HINT_PULL_MIN_NANOS && lastHintPull.compareAndSet(last, now)
                && !locks.get(Channel.MAIN).isLocked()) {
            // Not on the request thread: a repository that answers slowly must not hold up the calls. tryLock, not lock: when
            // the scheduled pull of the channel has just taken it, this one is not needed and must not wait on it.
            pool.execute(() -> {
                ReentrantLock lock = locks.get(Channel.MAIN);
                if (lock.tryLock()) {
                    try {
                        pull(Channel.MAIN);
                    } finally {
                        lock.unlock();
                    }
                }
            });
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
