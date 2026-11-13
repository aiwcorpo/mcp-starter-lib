package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/** With session.max-count set, an initialize beyond the cap is refused and sessions already open keep working. */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.name=platform-session-cap-test",
                "aiwcorpo.mcp.platform.session.max-count=3",
                "aiwcorpo.mcp.platform.session.idle-timeout=1s"})
@ActiveProfiles("dev")
@DisplayName("MCP session cap")
class SessionCapTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");
    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
            "capabilities":{},"clientInfo":{"name":"platform-test","version":"1"}}}""";
    private static final String TOOLS_LIST = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}";

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

    @Autowired
    WebMvcStreamableServerTransportProvider transport;

    @SuppressWarnings("unchecked")
    private Map<String, Object> sessions() throws Exception {
        Field field = WebMvcStreamableServerTransportProvider.class.getDeclaredField("sessions");
        field.setAccessible(true);
        return (Map<String, Object>) field.get(transport);
    }

    @Test
    @DisplayName("refuses an initialize at the cap with 503, also one with a stale or open session id, and admits again once one expires")
    void initializeBeyondTheCapIsRefused() throws Exception {
        RawMcp raw = new RawMcp(port);
        String kept = null;
        while (sessions().size() < 3) {
            kept = raw.openSession(GATEWAY);
        }

        HttpResponse<String> refused = raw.post(INITIALIZE, GATEWAY.user().mint().headers(), null);

        assertThat(refused.statusCode()).isEqualTo(503);
        assertThat(refused.headers().firstValue("Retry-After")).hasValue("5");
        assertThat(refused.body()).contains("\"error\":\"upstream_unavailable\"");
        assertThat(refused.headers().firstValue("Mcp-Session-Id")).isEmpty();
        assertThat(sessions()).hasSize(3);
        assertThat(raw.post(TOOLS_LIST, GATEWAY.user().mint().headers(), kept).statusCode()).isEqualTo(200);

        // The transport opens a session for any initialize, whatever Mcp-Session-Id it carries.
        String stale = "00000000-0000-0000-0000-000000000000";
        assertThat(raw.post(INITIALIZE, GATEWAY.user().mint().headers(), stale).statusCode()).isEqualTo(503);
        assertThat(raw.post(INITIALIZE, GATEWAY.user().mint().headers(), kept).statusCode()).isEqualTo(503);
        assertThat(sessions()).hasSize(3);

        Thread.sleep(5_000);

        assertThat(raw.post(INITIALIZE, GATEWAY.user().mint().headers(), null).statusCode()).isEqualTo(200);
    }
}
