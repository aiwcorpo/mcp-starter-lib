package pl.aiwcorpo.mcp.platform.policy;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.aiwcorpo.mcp.cedar.entities.ResourceTypes;
import pl.aiwcorpo.mcp.manifest.PolicyDataGate;

/**
 * The policy data a server decides with: what the gateway pushed to {@code POST /.well-known/mcp-policy-data} (C14), taken together
 * with the policies of the server's own jar ({@link ServerPolicies}) into a {@link PolicySnapshot} that is swapped atomically. The
 * server fetches nothing: it holds nothing until the first push, and loses everything when it stops, which is why the gateway pushes after
 * every change and periodically.
 *
 * <p>Fail-closed, by the rule of the contracts ({@link PolicyDataGate}): a call is evaluated only when data is held that is at least as new as
 * the {@code prv} of its token, the administrator has approved the {@code policiesHash} of this server's own policies and resource types,
 * and every override validates. Data that is missing or older than {@code prv} is {@code policy_data_stale} (the endpoint answers 409 and the
 * gateway pushes and repeats the call once); the other refusals are {@code upstream_unavailable}. Data with an override that does not validate is
 * taken, and refuses every call: the push is not refused, so that the administrator can see why. A revision that is not strictly higher than the
 * one held never replaces it (revisions are a counter that only grows).
 */
public final class PolicyEngine {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyEngine.class);

    /**
     * What a push leaves behind, the body of the answer of {@code POST /.well-known/mcp-policy-data}.
     *
     * @param revision revision held after the push
     * @param accepted true when the pushed data replaced the data held
     * @param gate     what the gate now decides: {@code ok}, {@code policies_not_approved}, {@code policies_differ} or {@code overrides_invalid}
     */
    public record Pushed(long revision, boolean accepted, String gate) {
    }

    private final ServerPolicies policies;
    private final MeterRegistry meters;
    private final Object swapping = new Object();
    private volatile PolicySnapshot current;

    private PolicyEngine(ServerPolicies policies, MeterRegistry meters) {
        this.policies = policies;
        this.meters = meters;
    }

    /** An engine that holds no data yet and refuses every call as {@code policy_data_stale} until the gateway pushes some. */
    public static PolicyEngine create(ServerPolicies policies, MeterRegistry meters) {
        return new PolicyEngine(policies, meters);
    }

    /**
     * An engine that holds {@code data}, for a test of the checks of a server without a gateway.
     *
     * @throws IllegalArgumentException when the data cannot be used for the policies
     */
    public static PolicyEngine fixed(ServerPolicies policies, PolicyData data) {
        PolicyEngine engine = new PolicyEngine(policies, null);
        engine.current = PolicySnapshot.of(policies, data);
        return engine;
    }

    /**
     * Takes pushed data when its revision is strictly newer than the one held (or none is held), otherwise keeps what is held.
     *
     * @throws IllegalArgumentException when the data cannot be used for the policies of this server; nothing changes then
     */
    public Pushed accept(PolicyData data) {
        synchronized (swapping) {
            PolicySnapshot held = current;
            if (held != null && !PolicyDataGate.acceptRevision(OptionalLong.of(held.revision()), data.revision())) {
                count("mcp_server_policy_data_rejected_total", data.revision() < held.revision() ? "older" : "same");
                LOG.info("event=policy_data_not_newer revision={} held={}", data.revision(), held.revision());
                return new Pushed(held.revision(), false, gateOf(held));
            }
            PolicySnapshot made;
            try {
                made = PolicySnapshot.of(policies, data);
            } catch (IllegalArgumentException ex) {
                count("mcp_server_policy_data_rejected_total", "unusable");
                LOG.warn("event=policy_data_rejected revision={} detail=\"{}\" previous=\"previous data stays active\"", data.revision(),
                        clean(ex.getMessage()));
                throw ex;
            }
            current = made;
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
            return new Pushed(made.revision(), true, gateOf(made));
        }
    }

    private String gateOf(PolicySnapshot snapshot) {
        return PolicyDataGate.decide(policies.policiesHash(), snapshot.gateData(), OptionalLong.empty()).reason();
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
     * @throws PolicyUnavailableException when no policy data has been pushed yet
     */
    public PolicySnapshot snapshot() {
        PolicySnapshot snapshot = current;
        if (snapshot == null) {
            throw new PolicyUnavailableException("no policy data is held");
        }
        return snapshot;
    }

    /** Revision of the policy data in use, empty before the first push. */
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
     *
     * @param heldRevision the revision held when the decision was made, empty when none was
     * @param requiredRevision {@code prv} of the token, empty when it has none (or 0, a gateway without policy data)
     */
    public record Gated(PolicyDataGate.Decision decision, PolicySnapshot snapshot, OptionalLong heldRevision, OptionalLong requiredRevision) {
    }

    /**
     * Whether a call may be evaluated, by {@link PolicyDataGate#decide}: data is held that is at least as new as {@code prv}, it approves the
     * {@code policiesHash} of this server's policies, and its overrides validate. The all-zero {@code prv} of a gateway without data
     * forces nothing. Nothing is fetched: stale data is refused, and the gateway pushes.
     */
    public Gated gate(OptionalLong prv) {
        PolicySnapshot held = current;
        OptionalLong asked = prv.isPresent() && prv.getAsLong() > 0 ? prv : OptionalLong.empty();
        PolicyDataGate.Decision decision = PolicyDataGate.decide(policies.policiesHash(), held == null ? null : held.gateData(), asked);
        return new Gated(decision, decision.allowed() ? held : null,
                held == null ? OptionalLong.empty() : OptionalLong.of(held.revision()), asked);
    }
}
