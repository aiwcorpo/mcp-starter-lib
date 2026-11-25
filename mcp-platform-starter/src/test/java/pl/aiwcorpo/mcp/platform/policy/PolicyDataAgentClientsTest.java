package pl.aiwcorpo.mcp.platform.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@code allowedAgentClients} of the policy data (CC-266): optional, absent is empty, and a malformed list refuses the document. */
class PolicyDataAgentClientsTest {

    private static PolicyData parse(String agentField) {
        String body = "{\"revision\":3,\"scopeMapping\":[],\"allowedClients\":[\"claude-code\"],\"overrides\":[]" + agentField + "}";
        return PolicyData.parse(body.replace("\\\\\"", "\"").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aBodyWithoutTheFieldHasNoAgentClients() {
        PolicyData data = parse("");

        assertThat(data.allowedAgentClients()).isEmpty();
        assertThat(data.allows("agent-reports", "agent")).isFalse();
    }

    @Test
    void theListedAgentClientsAreAcceptedForAgentsAndDelegatedCallsOnly() {
        PolicyData data = parse(",\"allowedAgentClients\":[\"agent-reports\"]");

        assertThat(data.allowedAgentClients()).containsExactly("agent-reports");
        assertThat(data.allows("agent-reports", "agent")).isTrue();
        assertThat(data.allows("agent-reports", "delegated")).isTrue();
        assertThat(data.allows("agent-reports", null)).isFalse();
        assertThat(data.allows("claude-code", "agent")).isFalse();
        assertThat(data.allows("claude-code", null)).isTrue();
        assertThat(data.allows(null, "agent")).isFalse();
    }

    @Test
    void aListThatIsNotAListOfNamesRefusesTheDocument() {
        assertThatThrownBy(() -> parse(",\"allowedAgentClients\":\"agent-reports\"")).hasMessageContaining("allowedAgentClients");
        assertThatThrownBy(() -> parse(",\"allowedAgentClients\":[1]")).hasMessageContaining("allowedAgentClients");
        assertThatThrownBy(() -> parse(",\"allowedAgentClients\":[\" \"]")).hasMessageContaining("allowedAgentClients");
    }

    @Test
    void theOtherConstructorsKeepTheirMeaning() {
        assertThat(PolicyData.of(1, java.util.Optional.empty(), java.util.Map.of(), List.of("claude-code"), List.of()).allowedAgentClients())
                .isEmpty();
    }
}
