package pl.aiwcorpo.mcp.platform.policy;

import com.cedarpolicy.AuthorizationEngine;
import com.cedarpolicy.BasicAuthorizationEngine;
import com.cedarpolicy.model.AuthorizationResponse;
import com.cedarpolicy.model.schema.Schema;
import com.cedarpolicy.model.exception.InternalException;
import com.cedarpolicy.model.policy.Policy;
import com.cedarpolicy.model.policy.PolicySet;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import pl.aiwcorpo.mcp.cedar.entities.CedarRequest;
import pl.aiwcorpo.mcp.cedar.entities.EntityBuilder;
import pl.aiwcorpo.mcp.cedar.entities.EntityBuilderException;
import pl.aiwcorpo.mcp.cedar.entities.McpCedarSchema;
import pl.aiwcorpo.mcp.cedar.entities.PlatformServerPolicies;
import pl.aiwcorpo.mcp.cedar.entities.PolicyFragments;
import pl.aiwcorpo.mcp.cedar.entities.ResultFilter;
import pl.aiwcorpo.mcp.cedar.entities.ScopeMapping;
import pl.aiwcorpo.mcp.manifest.PolicyDataGate;

/**
 * What one decision is made with: the policies of the server and the emergency overrides as one policy set over the composed
 * schema (the generated platform policies for the declared types included), the entity builder for the declared resource types and
 * the scope mapping of one revision of the policy data, and the allowed AI clients. Immutable; the engine swaps whole snapshots, so a call never sees a mix of two revisions.
 */
public final class PolicySnapshot {

    /**
     * The outcome of one evaluation.
     *
     * @param allowed     true only for an explicit allow without an evaluation error in the diagnostics (a forbid that errored on a missing
     *                    attribute must not open the door)
     * @param determining the ids of the policies that decided; an override has the prefix {@code override:}
     * @param errors      messages of the evaluation errors, empty when there were none
     */
    public record Evaluation(boolean allowed, Set<String> determining, List<String> errors) {
    }

    private final long revision;
    private final Optional<String> approvedPoliciesHash;
    private final boolean overridesValid;
    private final List<String> overrideProblems;
    private final List<ScopeMapping.Ignored> ignored;
    private final EntityBuilder entityBuilder;
    private final PolicySet policySet;
    private final PolicySet callPolicySet;
    private final Schema schema;
    private final AuthorizationEngine engine = new BasicAuthorizationEngine();
    private final Set<String> allowedClients;

    private PolicySnapshot(PolicyData data, boolean overridesValid, List<String> overrideProblems, List<ScopeMapping.Ignored> ignored,
                           EntityBuilder entityBuilder, PolicySet policySet, PolicySet callPolicySet, Schema schema) {
        this.revision = data.revision();
        this.approvedPoliciesHash = data.approvedPoliciesHash();
        this.overridesValid = overridesValid;
        this.overrideProblems = overrideProblems;
        this.ignored = ignored;
        this.allowedClients = Set.copyOf(data.allowedClients());
        this.entityBuilder = entityBuilder;
        this.policySet = policySet;
        this.callPolicySet = callPolicySet;
        this.schema = schema;
    }

    /**
     * The snapshot of {@code data} for the server with {@code policies}: the generated platform policies for the declared types, the
     * policies of the server and the overrides, merged into one set over the composed schema. Scope ids that evaluation cannot use (a
     * type this server does not declare, a local part that does not match the {@code scopePattern}) are skipped and listed in
     * {@link #ignored()}.
     *
     * <p>A document that could not be used (see {@link PolicyData#problems()}) gives a snapshot that grants nothing and refuses every call.
     * A snapshot with an override that does not validate against the composed schema, or whose policies cannot be merged (a
     * duplicate policy id), is still made but {@link #overridesValid()} is false and the server refuses every call with it: a forbid
     * that cannot be applied must not be dropped silently.
     *
     */
    public static PolicySnapshot of(ServerPolicies policies, PolicyData data) {
        List<String> problems = new ArrayList<>(data.problems());
        ScopeMapping mapping = ScopeMapping.empty();
        if (problems.isEmpty()) {
            try {
                mapping = ScopeMapping.fromJson(policies.resourceTypes(), data.scopeMapping(), true);
            } catch (EntityBuilderException ex) {
                problems.add("the scope mapping cannot be used: " + ex.getMessage());
            }
        }
        Set<Policy> accepted = new LinkedHashSet<>();
        for (PolicyData.Override override : data.overrides()) {
            PolicyFragments.Result result = PolicyFragments.validateOverride(override.id(), override.text(),
                    PolicyFragments.OverrideTarget.SERVER, List.of(policies.schemaText()));
            if (!result.ok()) {
                problems.add("override " + override.id() + ": " + String.join("; ", result.errors()));
                continue;
            }
            try {
                accepted.add(new Policy(PolicySet.parsePolicies(override.text()).policies.iterator().next().policySrc, override.id()));
            } catch (InternalException ex) {
                problems.add("override " + override.id() + " does not parse");
            }
        }
        PolicySet all = new PolicySet(Set.of());
        try {
            all = PolicyFragments.merge(PlatformServerPolicies.policySet(policies.resourceTypes()), policies.policies(), new PolicySet(accepted));
        } catch (IllegalArgumentException ex) {
            problems.add(ex.getMessage());
        }
        return new PolicySnapshot(data, problems.isEmpty(), List.copyOf(problems), mapping.ignored(),
                new EntityBuilder(policies.resourceTypes(), mapping), all, McpCedarSchema.platformPolicies(), policies.schema());
    }

    /** The revision of the policy data this snapshot was made from. */
    public long revision() {
        return revision;
    }

    /** The {@code policiesHash} the administrator approved for this server, empty while nothing is approved. */
    public Optional<String> approvedPoliciesHash() {
        return approvedPoliciesHash;
    }

    /** False when an override does not validate against the composed schema or the policies cannot be merged: no call is evaluated then. */
    public boolean overridesValid() {
        return overridesValid;
    }

    /** Why {@link #overridesValid()} is false, for the log. */
    public List<String> overrideProblems() {
        return overrideProblems;
    }

    /** The scopes and entries of the scope mapping that were skipped, for the log and the audit. */
    public List<ScopeMapping.Ignored> ignored() {
        return ignored;
    }

    /** What the gate of the contracts decides on: the revision, the approved hash and whether the overrides are valid. */
    public PolicyDataGate.Data gateData() {
        return new PolicyDataGate.Data(revision, approvedPoliciesHash, overridesValid);
    }

    /** The builder of Cedar requests; the credential type of the server is added by the caller ({@code withCredentialType}). */
    public EntityBuilder entityBuilder() {
        return entityBuilder;
    }

    /** The Cedar authorizer over the policies of the server and the overrides: only a clean allow counts. */
    public ResultFilter.Authorizer authorizer() {
        return request -> evaluate(request).allowed();
    }

    /**
     * Evaluates one request against the policies of the server and the overrides.
     *
     * @throws Exception when Cedar cannot evaluate the request at all (the caller treats that as a refusal)
     */
    public Evaluation evaluate(CedarRequest request) throws Exception {
        return evaluate(request, policySet);
    }

    /**
     * Evaluates {@code Action::"call"} on the tool with the platform policies of the contracts, the decision the gateway has made:
     * the server repeats it as defence in depth. Overrides are not part of it; they apply to the resources.
     */
    public Evaluation evaluateCall(CedarRequest request) throws Exception {
        return evaluate(request, callPolicySet);
    }

    private Evaluation evaluate(CedarRequest request, PolicySet policies) throws Exception {
        AuthorizationResponse response = engine.isAuthorized(request.toAuthorizationRequest(schema), policies, request.cedarEntities());
        if (response.success.isEmpty()) {
            return new Evaluation(false, Set.of(), List.of("Cedar could not evaluate the request"));
        }
        var success = response.success.get();
        List<String> errors = success.getErrors().stream().map(Object::toString).toList();
        return new Evaluation(success.isAllowed() && errors.isEmpty(), Set.copyOf(success.getReason()), errors);
    }

    /** The AI clients ({@code azp}) that may use the platform, from the gateway's configuration. */
    public Set<String> allowedClients() {
        return allowedClients;
    }

    /** Whether the AI client of a call is one the gateway allows; an empty list allows no one. */
    public boolean allows(String client) {
        return client != null && allowedClients.contains(client);
    }
}
