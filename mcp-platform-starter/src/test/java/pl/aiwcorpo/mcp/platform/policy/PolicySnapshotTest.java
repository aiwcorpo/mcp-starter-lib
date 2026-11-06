package pl.aiwcorpo.mcp.platform.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.Caller;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.cedar.entities.ToolDescriptor;

class PolicySnapshotTest {

    private static final ServerPolicies POLICIES = ServerPolicies.load(PolicySnapshotTest.class.getClassLoader());
    private static final long REVISION = 5;

    private static PolicySnapshot snapshot(Map<String, List<String>> mapping, List<String> clients, List<PolicyData.Override> overrides) {
        return PolicySnapshot.of(POLICIES, PolicyData.of(REVISION, Optional.of(POLICIES.policiesHash()), mapping, clients, overrides));
    }

    private static boolean allowed(PolicySnapshot snapshot, String action, String risk, String... groups) throws Exception {
        ResolvedResource project = ResolvedResource.of("Jira::Project", "ABC", Map.of("key", "ABC", "category", "Software"))
                .withScopes(List.of("Jira::Project/ABC"));
        ToolDescriptor tool = new ToolDescriptor("mcp-jira", "jira", "t", risk, action, List.of());
        return snapshot.evaluate(snapshot.entityBuilder().withCredentialType("user-oauth")
                .forResource(Caller.of("u-1", "jkowalski", List.of(groups), "claude-code"), tool, Map.of(), project)).allowed();
    }

    @Test
    void theScopeMappingDecidesWhatAnUpdateMayTouch() throws Exception {
        PolicySnapshot snapshot = snapshot(Map.of("mcp-jira-proj-ABC", List.of("Jira::Project/ABC")), List.of("claude-code"), List.of());

        assertThat(allowed(snapshot, "update", "UPDATE", "mcp-jira-write", "mcp-jira-proj-ABC")).isTrue();
        assertThat(allowed(snapshot, "update", "UPDATE", "mcp-jira-write")).isFalse();
        assertThat(allowed(snapshot, "read", "READ_ONLY", "mcp-jira-read")).isTrue();
    }

    @Test
    void aMappingThatIsNotAnArrayCannotBeUsedAtAll() {
        assertThatThrownBy(() -> PolicySnapshot.of(POLICIES, new PolicyData(REVISION, Optional.empty(), "{}", List.of(), List.of())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("scope mapping");
    }

    @Test
    void anOverrideForbidsAndItsIdIsThePolicyId() throws Exception {
        PolicyData.Override stop = new PolicyData.Override("override-stop", "forbid (principal, action, resource is Jira::Project);");
        PolicySnapshot snapshot = snapshot(Map.of(), List.of("claude-code"), List.of(stop));

        assertThat(snapshot.overridesValid()).isTrue();
        assertThat(allowed(snapshot, "read", "READ_ONLY", "mcp-jira-read")).isFalse();
    }

    @Test
    void anOverrideThatDoesNotValidateMakesTheSnapshotRefuseEverything() {
        for (String text : List.of("permit (principal, action, resource);",
                "forbid (principal, action, resource); forbid (principal, action, resource);", "not cedar",
                "forbid (principal, action == Action::\"call\", resource is Tool);",
                "forbid (principal, action, resource is Jira::Project) when { resource.nothing == 1 };")) {
            PolicySnapshot snapshot = snapshot(Map.of(), List.of("claude-code"), List.of(new PolicyData.Override("override-x", text)));

            assertThat(snapshot.overridesValid()).as(text).isFalse();
            assertThat(snapshot.overrideProblems()).as(text).isNotEmpty();
        }
        PolicySnapshot badId = snapshot(Map.of(), List.of("claude-code"),
                List.of(new PolicyData.Override("platform-call-by-group", "forbid (principal, action, resource);")));
        assertThat(badId.overridesValid()).isFalse();
    }

    @Test
    void theGeneratedPlatformPoliciesApplyWithoutTheServerWritingThem() throws Exception {
        PolicySnapshot snapshot = snapshot(Map.of("g", List.of("Jira::Project/ABC")), List.of("claude-code"), List.of());
        ResolvedResource restricted = ResolvedResource.of("Jira::Project", "HR", Map.of("key", "HR", "category", "restricted"))
                .withScopes(List.of("Jira::Project/HR"));
        ToolDescriptor tool = new ToolDescriptor("mcp-jira", "jira", "t", "READ_ONLY", "read", List.of());
        Caller user = Caller.of("u-1", "jkowalski", List.of("mcp-jira-read", "g"), "claude-code");

        PolicySnapshot.Evaluation account = snapshot.evaluate(snapshot.entityBuilder().withCredentialType("service-account")
                .forResource(user, tool, Map.of(), ResolvedResource.of("Jira::Project", "ABC", Map.of("key", "ABC", "category", ""))
                        .withScopes(List.of("Jira::Project/ZZZ"))));

        assertThat(account.allowed()).as("a service account never reads outside the scopes of the user").isFalse();
        assertThat(account.determining()).anyMatch(id -> id.startsWith("platform-"));
        assertThat(snapshot.evaluate(snapshot.entityBuilder().withCredentialType("user-oauth")
                .forResource(user, tool, Map.of(), restricted)).determining()).anyMatch(id -> id.startsWith("platform-"));
    }

    @Test
    void scopesThatCannotBeUsedAreListedNotSilentlyDropped() {
        PolicySnapshot snapshot = snapshot(Map.of("mcp-a", List.of("Confluence::Space/ENG", "Jira::Project/not a key", "Jira::Project/ABC")),
                List.of("claude-code"), List.of());

        assertThat(snapshot.ignored()).extracting(skipped -> skipped.reason()).containsExactlyInAnyOrder("undeclared_type", "invalid_scope");
        assertThat(snapshot.entityBuilder().queryConstraint(Caller.of("u", "n", List.of("mcp-a"), "c"), "Jira::Project").granted())
                .containsExactly("ABC");
    }

    @Test
    void theApprovedHashAndTheRevisionAreKept() {
        PolicySnapshot snapshot = snapshot(Map.of(), List.of("claude-code"), List.of());

        assertThat(snapshot.revision()).isEqualTo(REVISION);
        assertThat(snapshot.approvedPoliciesHash()).contains(POLICIES.policiesHash());
        assertThat(snapshot.gateData().overridesValid()).isTrue();
    }

    @Test
    void anEmptyListOfClientsAllowsNoOne() {
        assertThat(snapshot(Map.of(), List.of("claude-code"), List.of()).allows("claude-code")).isTrue();
        assertThat(snapshot(Map.of(), List.of("claude-code"), List.of()).allows("other")).isFalse();
        assertThat(snapshot(Map.of(), List.of(), List.of()).allows("claude-code")).isFalse();
    }
}
