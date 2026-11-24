package pl.aiwcorpo.mcp.platform.resource;

import static org.assertj.core.api.Assertions.assertThat;

import com.cedarpolicy.value.Value;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.Caller;
import pl.aiwcorpo.mcp.cedar.entities.CedarRequest;
import pl.aiwcorpo.mcp.cedar.entities.EntityBuilder;
import pl.aiwcorpo.mcp.cedar.entities.ScopeMapping;
import pl.aiwcorpo.mcp.cedar.entities.ToolDescriptor;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;

/** CC-263: the Cedar context of a call says who the principal is, from {@code pty} of the token, and {@code user} when it has none. */
class PrincipalTypeContextTest {

    private static final String SUB = "6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11";
    private static final String AGENT_SUB = "3b0f6c52-8f3e-4f0e-b6a2-5d5a1f0e9c11";

    private static CallerContext caller(String pty, String actorSub, String actorClient) {
        return new CallerContext(SUB, "jkowalski", List.of("mcp-jira-read"), "claude-code", "get_issue",
                "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", 1L, null, null, null, pty, actorSub, actorClient);
    }

    private static Map<String, Value> contextOf(CallerContext claims) {
        EntityBuilder builder = new EntityBuilder(ExampleTypes.ALL, ScopeMapping.empty());
        ToolDescriptor tool = new ToolDescriptor("mcp-jira", "jira", "get_issue", "READ_ONLY", "read", List.of());
        CedarRequest request = builder.forToolCall(ResourcePolicy.user(claims), tool, Map.of());
        return request.context();
    }

    @Test
    void aTokenWithoutPtyIsAUser() {
        assertThat(ResourcePolicy.user(caller(null, null, null)).principalType()).isEqualTo("user");
        assertThat(contextOf(caller(null, null, null)).get("principalType")).hasToString("user");
        assertThat(contextOf(caller(null, null, null))).doesNotContainKey("actor");
    }

    @Test
    void anAgentIsAnAgentWithoutAnActor() {
        Map<String, Value> context = contextOf(caller("agent", null, null));

        assertThat(context.get("principalType")).hasToString("agent");
        assertThat(context).doesNotContainKey("actor");
    }

    @Test
    void aDelegatedCallCarriesTheAgentThatActsAsTheActor() {
        Map<String, Value> context = contextOf(caller("delegated", AGENT_SUB, "agent-triage"));

        assertThat(context.get("principalType")).hasToString("delegated");
        assertThat(context.get("actor")).hasToString(AGENT_SUB);
        assertThat(ResourcePolicy.user(caller("delegated", AGENT_SUB, "agent-triage")).actor()).contains(AGENT_SUB);
    }

    @Test
    void theCallerOfAnAgentKeepsTheServiceAccountAsSubjectAndTheAgentClientAsClient() {
        CallerContext claims = new CallerContext(AGENT_SUB, "service-account-agent-triage", List.of("mcp-jira-read"), "agent-triage",
                "get_issue", "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", 1L, null, null, null, "agent", null, null);

        Caller user = ResourcePolicy.user(claims);

        assertThat(user.sub()).isEqualTo(AGENT_SUB);
        assertThat(user.username()).isEqualTo("service-account-agent-triage");
        assertThat(user.client()).isEqualTo("agent-triage");
    }
}
