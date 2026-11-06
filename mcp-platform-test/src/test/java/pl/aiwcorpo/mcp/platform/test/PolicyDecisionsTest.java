package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;

/** The harness a server repository uses to test its policies, run on the Jira fragment in {@code src/test/resources/mcp/policies}. */
class PolicyDecisionsTest {

    private static ResolvedResource project(String key, String category) {
        return ResolvedResource.of("Jira::Project", key, Map.of("key", key, "category", category))
                .withScopes(List.of("Jira::Project/" + key));
    }

    private static ResolvedResource issue(String key, ResolvedResource project) {
        return ResolvedResource.of("Jira::Issue", key, Map.of("key", key)).withParent(project).withScopes(project.scopes());
    }

    private final PolicyDecisions policies = PolicyDecisions.fromResources()
            .scopeMapping(Map.of("mcp-jira-proj-ABC", List.of("Jira::Project/ABC")));

    @Test
    void anUpdateNeedsTheScopeOfTheProjectAndTheGroupOfThePolicy() {
        ResolvedResource abc = issue("ABC-1", project("ABC", "Software"));
        ResolvedResource def = issue("DEF-1", project("DEF", "Software"));

        policies.assertAllowed(PolicyDecisions.caller("mcp-jira-write", "mcp-jira-proj-ABC"), "update", abc);
        policies.assertDenied(PolicyDecisions.caller("mcp-jira-write", "mcp-jira-proj-ABC"), "update", def);
        policies.assertDenied(PolicyDecisions.caller("mcp-jira-proj-ABC"), "update", abc);
        policies.assertDenied(PolicyDecisions.caller("mcp-jira-write"), "update", abc);
    }

    @Test
    void aRestrictedProjectIsDeniedWhateverTheCallerHas() {
        ResolvedResource hr = issue("HR-1", project("HR", " Restricted "));

        policies.assertDenied(PolicyDecisions.caller("mcp-jira-read", "mcp-jira-write", "mcp-jira-proj-ABC"), "read", hr);
        assertThat(policies.decide(PolicyDecisions.caller("mcp-jira-read"), "read", hr).determiningPolicies())
                .contains("jira-forbid-restricted-issue");
    }

    @Test
    void anOverrideAndTheAllowedClientsApplyLikeAtTheGateway() {
        ResolvedResource abc = issue("ABC-1", project("ABC", "Software"));
        PolicyDecisions withOverride = PolicyDecisions.fromResources()
                .override("override-no-reads", "forbid (principal, action == Jira::Action::\"read\", resource);");

        withOverride.assertDenied(PolicyDecisions.caller("mcp-jira-read"), "read", abc);
        assertThat(withOverride.decide(PolicyDecisions.caller("mcp-jira-read"), "read", abc).determiningPolicies())
                .containsExactly("override-no-reads");
        PolicyDecisions.fromResources().allowedClients("claude-ai")
                .assertDenied(PolicyDecisions.caller("mcp-jira-read"), "read", abc);
    }

    @Test
    void aResourceThatDoesNotMatchItsDeclarationIsAMistakeOfTheTestNotADecision() {
        ResolvedResource noCategory = ResolvedResource.of("Jira::Project", "ABC", Map.of("key", "ABC"))
                .withScopes(List.of("Jira::Project/ABC"));
        ResolvedResource badId = ResolvedResource.of("Jira::Project", "abc", Map.of("key", "abc", "category", ""));

        assertThatThrownBy(() -> policies.decide(PolicyDecisions.caller("mcp-jira-read"), "read", noCategory))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policies.decide(PolicyDecisions.caller("mcp-jira-read"), "read", badId))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policies.decide(PolicyDecisions.caller("mcp-jira-read"), "wipe", project("ABC", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAssertionThatDoesNotHoldFailsWithTheDecision() {
        assertThatThrownBy(() -> policies.assertAllowed(PolicyDecisions.caller("mcp-jira-write"), "update", project("ABC", "")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("expected allow").hasMessageContaining("deny");
    }
}
