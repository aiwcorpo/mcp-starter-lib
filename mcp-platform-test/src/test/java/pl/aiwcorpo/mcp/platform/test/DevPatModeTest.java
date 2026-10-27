package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/** {@code auth-mode=dev-pat}: the developer's own token in {@code X-PAT-<system>}; the internal JWT is still required. */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.type=SYNC",
                "spring.ai.mcp.server.name=platform-dev-pat-test", "aiwcorpo.mcp.platform.auth-mode=dev-pat",
                "spring.ai.mcp.server.streamable-http.mcp-endpoint=" + RawMcp.ENDPOINT})
@ActiveProfiles("dev")
@DisplayName("dev-pat mode")
class DevPatModeTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    @AfterAll
    static void stop() {
        GATEWAY.close();
    }

    @LocalServerPort
    int port;

    @Test
    void thePatOfTheServersSystemReachesTheTool() throws Exception {
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);
        Map<String, String> headers = new HashMap<>(GATEWAY.user().tool("probe_credential").mint().headers());
        headers.put("X-PAT-Jira", "dev-pat-of-developer-1234");

        HttpResponse<String> response = raw.post(RawMcp.toolCall("probe_credential"), headers, session);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("user-secret Bearer dev-pat-of-developer-1234");
    }

    @Test
    void aPatDoesNotReplaceTheInternalJwt() throws Exception {
        HttpResponse<String> response = new RawMcp(port).post(RawMcp.toolCall("probe_credential"),
                Map.of("X-PAT-Jira", "dev-pat-of-developer-1234"), null);

        assertThat(response.statusCode()).isEqualTo(401);
    }
}
