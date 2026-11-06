package pl.aiwcorpo.mcp.platform.test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.aiwcorpo.mcp.cedar.entities.Caller;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.cedar.entities.ToolDescriptor;
import pl.aiwcorpo.mcp.platform.policy.PolicyData;
import pl.aiwcorpo.mcp.platform.policy.PolicySnapshot;
import pl.aiwcorpo.mcp.platform.policy.ServerPolicies;

/**
 * Decision tests for the policies of a server, in plain JUnit: loads {@code mcp/policies/} of the server from its resources (the files
 * the manifest and the gateway get, validated the same way), takes the scope mapping, allowed clients and overrides a test gives and
 * asks whether a caller may do an action on a resource. No Spring context, no gateway, no backend.
 *
 * <pre>{@code
 * PolicyDecisions policies = PolicyDecisions.fromResources()
 *         .scopeMapping(Map.of("mcp-jira-proj-ABC", List.of("Jira::Project/ABC")));
 * ResolvedResource issue = ResolvedResource.of("Jira::Issue", "ABC-1", Map.of("key", "ABC-1"))
 *         .withParent(project).withScopes(List.of("Jira::Project/ABC"));
 *
 * policies.assertAllowed(PolicyDecisions.caller("mcp-jira-write", "mcp-jira-proj-ABC"), "update", issue);
 * policies.assertDenied(PolicyDecisions.caller("mcp-jira-write"), "update", issue);
 * }</pre>
 *
 * The resource is what the resolver of the server would produce: attributes as declared in {@code resource-types.json}, the parent,
 * and {@code scopes}. The request is built by the same entity builder as in the server, so a resource that does not match its
 * declaration is refused here as it would be there ({@link IllegalArgumentException}).
 */
public final class PolicyDecisions {

    /** The caller of tests that do not care who it is; {@link #caller(String...)} gives the groups. */
    public static final String DEFAULT_CLIENT = "claude-code";

    private static final long REVISION = 1;
    private static final String DEFAULT_CREDENTIAL_TYPE = "user-secret";

    /** The outcome of a decision. */
    public record Decision(boolean allowed, Set<String> determiningPolicies, List<String> errors) {

        @Override
        public String toString() {
            return (allowed ? "allow" : "deny") + " by " + determiningPolicies + (errors.isEmpty() ? "" : " with errors " + errors);
        }
    }

    private final ServerPolicies policies;
    private Map<String, List<String>> scopeMapping = Map.of();
    private List<String> allowedClients = List.of(DEFAULT_CLIENT);
    private final List<PolicyData.Override> overrides = new ArrayList<>();
    private String credentialType = DEFAULT_CREDENTIAL_TYPE;
    private PolicySnapshot snapshot;

    private PolicyDecisions(ServerPolicies policies) {
        this.policies = policies;
    }

    /**
     * The policies in {@code src/main/resources/mcp/policies/} of the project under test: they must be files of a directory of the classpath
     * (its {@code target/classes}), not of a dependency jar.
     *
     * @throws IllegalStateException with every reason when they are missing or not valid: the same message the server's start gives
     */
    public static PolicyDecisions fromResources() {
        return new PolicyDecisions(ServerPolicies.loadFromDirectory(PolicyDecisions.class.getClassLoader()));
    }

    /** The policies of the application whose code contains {@code application}: the check the server makes at start. */
    public static PolicyDecisions fromResources(Class<?> application) {
        return new PolicyDecisions(ServerPolicies.load(PolicyDecisions.class.getClassLoader(), java.util.List.of(application)));
    }

    /** The same for policies given as text, for a test of a fragment under construction. */
    public static PolicyDecisions of(ServerPolicies policies) {
        return new PolicyDecisions(policies);
    }

    /** Which group grants which scopes ({@code <Type>/<id>}): what an administrator sets in the gateway. A group not listed grants nothing. */
    public PolicyDecisions scopeMapping(Map<String, List<String>> scopesByGroup) {
        this.scopeMapping = Map.copyOf(scopesByGroup);
        this.snapshot = null;
        return this;
    }

    /** The AI clients the gateway allows (default: {@value #DEFAULT_CLIENT}). */
    public PolicyDecisions allowedClients(String... clients) {
        this.allowedClients = List.of(clients);
        this.snapshot = null;
        return this;
    }

    /** An emergency override an administrator set for this server: its id ({@code override-<name>}) and the Cedar text of one {@code forbid} policy. */
    public PolicyDecisions override(String id, String text) {
        overrides.add(new PolicyData.Override(id, text));
        this.snapshot = null;
        return this;
    }

    /** The credential type of the request ({@code context.credentialType}); default {@value #DEFAULT_CREDENTIAL_TYPE}. */
    public PolicyDecisions credentialType(String type) {
        this.credentialType = type;
        return this;
    }

    /** A caller in the given groups, as the internal JWT of a call from {@value #DEFAULT_CLIENT} would describe it. */
    public static Caller caller(String... groups) {
        return Caller.of("6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11", "jkowalski", List.of(groups), DEFAULT_CLIENT);
    }

    /**
     * Whether {@code caller} may do {@code action} ({@code read}, {@code update}, {@code delete} or {@code admin}) on {@code resource}.
     * The tool risk follows from the action.
     *
     * @throws IllegalArgumentException when the resource does not match its declaration or the data cannot be used
     */
    public Decision decide(Caller caller, String action, ResolvedResource resource) {
        PolicySnapshot current = snapshot();
        if (!current.allows(caller.client())) {
            return new Decision(false, Set.of(), List.of("client " + caller.client() + " is not allowed by the policy data"));
        }
        ToolDescriptor tool = new ToolDescriptor("mcp-test", "test", "decision_test", riskOf(action), action, List.of());
        if (!policies.resourceTypes().isValidId(resource.type(), resource.id())) {
            throw new IllegalArgumentException("the id " + resource.id() + " does not match the idPattern of " + resource.type());
        }
        try {
            PolicySnapshot.Evaluation result = current.evaluate(current.entityBuilder().withCredentialType(credentialType)
                    .forResource(caller, tool, Map.of(), resource));
            return new Decision(result.allowed(), result.determining(), result.errors());
        } catch (pl.aiwcorpo.mcp.cedar.entities.EntityBuilderException ex) {
            throw new IllegalArgumentException("the resource does not match its declaration: " + ex.getMessage(), ex);
        } catch (Exception ex) {
            throw new IllegalStateException("the request could not be evaluated: " + ex.getMessage(), ex);
        }
    }

    /** Fails with the decision when the caller may not. */
    public PolicyDecisions assertAllowed(Caller caller, String action, ResolvedResource resource) {
        Decision decision = decide(caller, action, resource);
        if (!decision.allowed()) {
            throw new AssertionError("expected allow for " + describe(caller, action, resource) + " but got " + decision);
        }
        return this;
    }

    /** Fails when the caller may. A resource that errors in a policy counts as denied, like in the server. */
    public PolicyDecisions assertDenied(Caller caller, String action, ResolvedResource resource) {
        Decision decision = decide(caller, action, resource);
        if (decision.allowed()) {
            throw new AssertionError("expected deny for " + describe(caller, action, resource) + " but got " + decision);
        }
        return this;
    }

    /** The policies under test, for a test that looks at the declarations or the texts. */
    public ServerPolicies serverPolicies() {
        return policies;
    }

    private PolicySnapshot snapshot() {
        if (snapshot == null) {
            PolicySnapshot made = PolicySnapshot.of(policies, PolicyData.of(REVISION, java.util.Optional.of(policies.policiesHash()), toMutable(scopeMapping), allowedClients, overrides));
            if (!made.overridesValid()) {
                throw new IllegalArgumentException("an override is not valid for these policies, the server would refuse every call: "
                        + made.overrideProblems());
            }
            snapshot = made;
        }
        return snapshot;
    }

    private static Map<String, List<String>> toMutable(Map<String, List<String>> mapping) {
        return new java.util.TreeMap<>(mapping);
    }

    private static String riskOf(String action) {
        return switch (action) {
            case "read" -> "READ_ONLY";
            case "update", "admin" -> "UPDATE";
            case "delete" -> "DESTRUCTIVE";
            default -> throw new IllegalArgumentException("action must be read, update, delete or admin, not " + action);
        };
    }

    private static String describe(Caller caller, String action, ResolvedResource resource) {
        return caller.groups() + " " + action + " " + resource.type() + "::" + resource.id();
    }
}
