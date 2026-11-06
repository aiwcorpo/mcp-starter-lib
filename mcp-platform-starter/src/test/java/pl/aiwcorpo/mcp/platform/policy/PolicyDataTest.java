package pl.aiwcorpo.mcp.platform.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PolicyDataTest {

    private static byte[] json(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theDocumentOfTheContractIsRead() {
        PolicyData data = PolicyData.parse(json("""
                {"revision":7,
                 "approvedPoliciesHash":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                 "scopeMapping":[{"group":"mcp-jira-proj-ABC","scopes":["Jira::Project/ABC"]}],
                 "allowedClients":["claude-code","claude-ai"],
                 "overrides":[{"id":"override-block","text":"forbid (principal, action, resource);"}]}"""));

        assertThat(data.revision()).isEqualTo(7);
        assertThat(data.approvedPoliciesHash()).contains("sha256:" + "a".repeat(64));
        assertThat(data.allowedClients()).containsExactly("claude-code", "claude-ai");
        assertThat(data.overrides()).containsExactly(new PolicyData.Override("override-block", "forbid (principal, action, resource);"));
        assertThat(data.scopeMapping()).contains("mcp-jira-proj-ABC");
    }

    @Test
    void theApprovedHashIsOptionalButMustBeAHashWhenThere() {
        PolicyData none = PolicyData.parse(json("{\"revision\":1,\"scopeMapping\":[],\"allowedClients\":[],\"overrides\":[]}"));

        assertThat(none.approvedPoliciesHash()).isEmpty();
        assertThatThrownBy(() -> PolicyData.parse(json(
                "{\"revision\":1,\"approvedPoliciesHash\":\"abc\",\"scopeMapping\":[],\"allowedClients\":[],\"overrides\":[]}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void whatIsNotThatDocumentIsRefused() {
        for (String bad : new String[] {
                "not json", "[]", "{}",
                "{\"revision\":0,\"scopeMapping\":[],\"allowedClients\":[],\"overrides\":[]}",
                "{\"revision\":\"20261106093000-a1b2c3d\",\"scopeMapping\":[],\"allowedClients\":[],\"overrides\":[]}",
                "{\"revision\":1.5,\"scopeMapping\":[],\"allowedClients\":[],\"overrides\":[]}",
                "{\"revision\":1,\"scopeMapping\":{},\"allowedClients\":[],\"overrides\":[]}",
                "{\"revision\":1,\"scopeMapping\":[],\"allowedClients\":[1],\"overrides\":[]}",
                "{\"revision\":1,\"scopeMapping\":[],\"allowedClients\":[],\"overrides\":[{\"id\":\"a\",\"text\":\"x\"},{\"id\":\"a\",\"text\":\"y\"}]}",
                "{\"revision\":1,\"revision\":2,\"scopeMapping\":[],\"allowedClients\":[],\"overrides\":[]}"}) {
            assertThatThrownBy(() -> PolicyData.parse(json(bad))).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
