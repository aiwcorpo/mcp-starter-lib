package pl.aiwcorpo.mcp.platform.policy;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.aiwcorpo.mcp.cedar.entities.ResourceTypes;
import pl.aiwcorpo.mcp.manifest.PolicyDataGate;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;

/**
 * The policy data a server decides with: what the gateway serves at {@code GET /internal/v1/policy-data} (C7) over mTLS, taken
 * together with the policies of the server's own jar ({@link ServerPolicies}) into a {@link PolicySnapshot} that is swapped
 * atomically.
 *
 * <p>Fail-closed, by the rule of the contracts ({@link PolicyDataGate}): a call is evaluated only when data is loaded, the
 * administrator has approved the {@code policiesHash} of this server's own policies and resource types, and every override
 * validates; otherwise it is refused as {@code upstream_unavailable}. A server whose gateway cannot be reached at start, or that is
 * not registered there yet, starts all the same and keeps asking. Data that cannot be used at all leaves the previous snapshot in
 * use and is counted and logged; data with an override that does not validate is taken and refuses every call. A revision that is not
 * higher than the one held is never taken (revisions are a counter that only grows).
 *
 * <p>The data is asked for every 30 s unless configured otherwise, with {@code If-None-Match}, and every 5 s while there is none. A call
 * whose token names a revision {@code prv} newer than the loaded one makes the engine ask before the call is evaluated, and refuses it
 * when the answer is not at least that new.
 */
public final class PolicyEngine implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyEngine.class);
    /** After a request for a revision failed, a request for the same or a newer one waits this long before it is made again. */
    private static final long NEGATIVE_CACHE_NANOS = TimeUnit.SECONDS.toNanos(3);

    private final ServerPolicies policies;
    private final PolicyDataClient client;
    private final URI uri;
    private final Duration refreshInterval;
    private final Duration retryInterval;
    private final Duration startupTimeout;
    private final Duration readTimeout;
    private final MeterRegistry meters;
    private final ReentrantLock pulling = new ReentrantLock();
    private volatile PolicySnapshot current;
    private String etag;
    private long rejectedRevision = -1;
    private volatile ScheduledExecutorService scheduler;
    private final Map<Long, CompletableFuture<Void>> flights = new HashMap<>();
    private final java.util.concurrent.ExecutorService requests = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "policy-data-refresh");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long lastFailedNanos;
    private volatile long lastFailedTarget;

    private PolicyEngine(ServerPolicies policies, PolicyDataClient client, URI uri, Duration refreshInterval, Duration retryInterval,
                         Duration startupTimeout, Duration readTimeout, MeterRegistry meters) {
        this.policies = policies;
        this.client = client;
        this.uri = uri;
        this.refreshInterval = refreshInterval;
        this.retryInterval = retryInterval;
        this.startupTimeout = startupTimeout;
        this.readTimeout = readTimeout;
        this.meters = meters;
    }

    /**
     * The engine for the configured gateway, started: the first policy data is asked for and the periodic refresh begins. When
     * nothing could be loaded within the startup timeout the engine is returned all the same and refuses until it has data.
     *
     * @param sslContext the context with the server's own certificate for mTLS, or {@code null} for plain http in the dev profile
     */
    public static PolicyEngine create(McpPlatformProperties.PolicyData config, ServerPolicies policies, SSLContext sslContext,
                                      MeterRegistry meters) {
        PolicyEngine engine = new PolicyEngine(policies, new PolicyDataClient(sslContext, config.getConnectTimeout(), config.getReadTimeout()),
                URI.create(config.getUrl()), config.getRefreshInterval(), config.getRetryInterval(), config.getStartupTimeout(), config.getReadTimeout(), meters);
        engine.start();
        return engine;
    }

    /**
     * An engine that holds {@code data} and never asks anyone: for a test of the checks of a server without a gateway.
     *
     * @throws IllegalArgumentException when the data cannot be used for the policies
     */
    public static PolicyEngine fixed(ServerPolicies policies, PolicyData data) {
        PolicyEngine engine = new PolicyEngine(policies, null, null, Duration.ofDays(1), Duration.ofDays(1), Duration.ZERO, Duration.ofSeconds(1), null);
        engine.current = PolicySnapshot.of(policies, data);
        return engine;
    }

    /** Asks for the first data (for at most the startup timeout) and starts the periodic refresh. */
    public void start() {
        long deadline = System.nanoTime() + startupTimeout.toNanos();
        refresh();
        while (current == null && System.nanoTime() < deadline) {
            sleep(Math.min(500, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))));
            refresh();
        }
        if (current == null) {
            LOG.warn("event=policy_data_missing detail=\"no policy data could be loaded from {} within {} s; calls are refused until it "
                    + "is (is the server registered at the gateway?)\"", uri, startupTimeout.toSeconds());
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "policy-data-pull");
            thread.setDaemon(true);
            return thread;
        });
        scheduleNext();
    }

    private void scheduleNext() {
        ScheduledExecutorService pool = scheduler;
        if (pool == null || pool.isShutdown()) {
            return;
        }
        Duration delay = current == null ? retryInterval : refreshInterval;
        pool.schedule(() -> {
            try {
                refresh();
            } finally {
                scheduleNext();
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the policy data", ex);
        }
    }

    /** One request for the data; never throws: a failure leaves the active snapshot in place. */
    public void refresh() {
        pulling.lock();
        try {
            pull();
        } finally {
            pulling.unlock();
        }
    }

    private void pull() {
        if (client == null) {
            return;
        }
        try {
            Optional<PolicyDataClient.Fetched> fetched = client.fetch(uri, etag);
            if (fetched.isEmpty()) {
                return;
            }
            PolicyData data = fetched.get().data();
            PolicySnapshot loaded = current;
            if (loaded != null && !PolicyDataGate.acceptRevision(OptionalLong.of(loaded.revision()), data.revision())) {
                if (data.revision() < loaded.revision()) {
                    count("mcp_server_policy_data_rejected_total", "older");
                    LOG.warn("event=policy_data_rejected revision={} detail=\"older than revision {} in use; previous data stays active\"",
                            data.revision(), loaded.revision());
                } else {
                    etag = fetched.get().etag();
                }
                return;
            }
            if (data.revision() == rejectedRevision) {
                return;
            }
            try {
                PolicySnapshot made = PolicySnapshot.of(policies, data);
                current = made;
                etag = fetched.get().etag();
                rejectedRevision = -1;
                LOG.info("event=policy_data_loaded revision={} overrides={} approved={}", data.revision(), data.overrides().size(),
                        data.approvedPoliciesHash().isPresent());
                made.ignored().forEach(skipped -> {
                    count("mcp_server_policy_data_scope_ignored_total", skipped.reason());
                    LOG.warn("event=policy_data_scope_ignored revision={} group=\"{}\" scope=\"{}\" reason={}", data.revision(),
                            clean(skipped.group()), clean(skipped.scope()), skipped.reason());
                });
                if (!made.overridesValid()) {
                    count("mcp_server_policy_data_rejected_total", "override_invalid");
                    LOG.error("event=policy_data_overrides_invalid revision={} detail=\"every call is refused until the overrides validate: {}\"",
                            data.revision(), clean(String.join(" | ", made.overrideProblems())));
                }
            } catch (IllegalArgumentException ex) {
                rejectedRevision = data.revision();
                etag = fetched.get().etag();
                count("mcp_server_policy_data_rejected_total", "unusable");
                LOG.warn("event=policy_data_rejected revision={} detail=\"{}\"{}", data.revision(), clean(ex.getMessage()),
                        loaded == null ? "" : " previous=\"previous data stays active\"");
            }
        } catch (IOException ex) {
            count("mcp_server_policy_data_fetch_failed_total", "io");
            LOG.warn("event=policy_data_fetch_failed detail=\"{}\"", ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            count("mcp_server_policy_data_fetch_failed_total", "error");
            LOG.error("event=policy_data_fetch_failed detail=\"{}\"", ex.getClass().getName());
        }
    }

    /** For a log line: no quote, no line break, bounded; the text may come from the data of an administrator. */
    private static String clean(String text) {
        String flat = text == null ? "" : text.replaceAll("[\\p{Cntrl}\"]", "'");
        return flat.length() > 300 ? flat.substring(0, 300) : flat;
    }

    private void count(String name, String reason) {
        if (meters != null) {
            meters.counter(name, "reason", reason).increment();
        }
    }

    /**
     * The snapshot to decide with.
     *
     * @throws PolicyUnavailableException when no policy data has been loaded yet
     */
    public PolicySnapshot snapshot() {
        PolicySnapshot snapshot = current;
        if (snapshot == null) {
            throw new PolicyUnavailableException("no policy data is loaded");
        }
        return snapshot;
    }

    /** Revision of the policy data in use, empty before the first load. */
    public OptionalLong revision() {
        PolicySnapshot snapshot = current;
        return snapshot == null ? OptionalLong.empty() : OptionalLong.of(snapshot.revision());
    }

    /** The resource types of this server, from its own policies: they do not change while it runs. */
    public ResourceTypes resourceTypes() {
        return policies.resourceTypes();
    }

    /** The policies of this server's jar. */
    public ServerPolicies policies() {
        return policies;
    }

    /**
     * The outcome of {@link #gate}: the decision and the one snapshot a call that is let through must decide with, in every step, so a
     * swap of the data after the gate cannot change what the call is judged by. The snapshot is {@code null} exactly when the decision refuses.
     */
    public record Gated(PolicyDataGate.Decision decision, PolicySnapshot snapshot) {
    }

    /**
     * Whether a call may be evaluated, by {@link PolicyDataGate#decide}: data is held, it approves the {@code policiesHash} of this server's
     * policies, and its overrides validate. When {@code prv} of the token is newer than the data held, the engine asks the gateway before
     * it decides and decides with the answer, or refuses when the answer is not at least as new. The all-zero {@code prv} of a gateway without
     * data forces nothing.
     *
     * <p>Callers that need the same revision share one request ({@link #refreshTo}), wait for it at most {@code read-timeout}, and a failed
     * request is not repeated for a few seconds, so a flood of calls with an unreachable {@code prv} costs the gateway one request, not one per
     * call.
     */
    public Gated gate(OptionalLong prv) {
        PolicySnapshot held = current;
        OptionalLong asked = prv.isPresent() && prv.getAsLong() > 0 ? prv : OptionalLong.empty();
        PolicySnapshot refreshed = null;
        if (asked.isPresent() && (held == null || asked.getAsLong() > held.revision()) && client != null) {
            refreshed = refreshTo(asked.getAsLong());
        }
        boolean useRefreshed = asked.isPresent() && (held == null || asked.getAsLong() > held.revision());
        PolicySnapshot used = useRefreshed ? refreshed : held;
        PolicyDataGate.Decision decision = PolicyDataGate.decide(policies.policiesHash(), held == null ? null : held.gateData(), asked,
                refreshed == null ? null : refreshed.gateData());
        return new Gated(decision, decision.allowed() ? used : null);
    }

    /** Asks the gateway for data at least as new as {@code target} and returns the snapshot then in use (possibly still older, or null). */
    private PolicySnapshot refreshTo(long target) {
        PolicySnapshot now = current;
        if (now != null && now.revision() >= target) {
            return now;
        }
        long failedAt = lastFailedNanos;
        if (failedAt != 0 && target >= lastFailedTarget && System.nanoTime() - failedAt < NEGATIVE_CACHE_NANOS) {
            return now;
        }
        CompletableFuture<Void> flight;
        boolean leader = false;
        synchronized (flights) {
            flight = flights.get(target);
            if (flight == null) {
                flight = new CompletableFuture<>();
                flights.put(target, flight);
                leader = true;
            }
        }
        if (leader) {
            CompletableFuture<Void> mine = flight;
            requests.execute(() -> {
                try {
                    refresh();
                } finally {
                    synchronized (flights) {
                        flights.remove(target);
                    }
                    mine.complete(null);
                }
            });
        }
        try {
            flight.get(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException ex) {
            // The caller decides with what is held: too old for this prv, so the gate refuses.
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        now = current;
        if (now == null || now.revision() < target) {
            lastFailedTarget = target;
            lastFailedNanos = System.nanoTime();
        }
        return now;
    }

    @Override
    public void close() {
        ScheduledExecutorService pool = scheduler;
        if (pool != null) {
            pool.shutdownNow();
        }
        requests.shutdownNow();
    }
}
