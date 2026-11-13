package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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

/** Sessions the gateway drops without DELETE must not stay in the transport until the server restarts. */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.name=platform-session-expiry-test",
                "aiwcorpo.mcp.platform.session.idle-timeout=1s"})
@ActiveProfiles("dev")
@DisplayName("idle MCP sessions")
class SessionIdleExpiryTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");
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
    @DisplayName("are removed after the idle timeout without a DELETE, and their id then answers 404")
    void idleSessionsExpire() throws Exception {
        RawMcp raw = new RawMcp(port);
        int before = sessions().size();
        List<String> opened = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            opened.add(raw.openSession(GATEWAY));
        }
        assertThat(sessions().keySet()).containsAll(opened);

        Thread.sleep(5_000);

        assertThat(sessions().keySet()).doesNotContainAnyElementsOf(opened);
        assertThat(sessions()).hasSizeLessThanOrEqualTo(before);
        HttpResponse<String> expired = raw.post(TOOLS_LIST, GATEWAY.user().mint().headers(), opened.getFirst());
        assertThat(expired.statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("stay while they are used")
    void anActiveSessionStays() throws Exception {
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);
        for (int i = 0; i < 8; i++) {
            Thread.sleep(500);
            assertThat(raw.post(TOOLS_LIST, GATEWAY.user().mint().headers(), session).statusCode()).isEqualTo(200);
        }
        assertThat(sessions().keySet()).contains(session);
    }
}
