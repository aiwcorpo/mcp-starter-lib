package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.listbean.ListBeanServer;
import pl.aiwcorpo.mcp.platform.test.listbean.ListBeanServer.ListedTools;

/** Tools declared in a {@code List<ToolCallback>} bean go through the platform's decorator like those of a provider. */
@SpringBootTest(classes = ListBeanServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.name=platform-list-bean-test"})
@ActiveProfiles("dev")
@DisplayName("tools in a List<ToolCallback> bean")
class ListBeanToolTest {

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
    @DisplayName("a call with an argument outside the schema is refused and audited, the tool is not reached")
    void aCallWithAnArgumentOutsideTheSchemaIsRefused() throws Exception {
        Logger audit = (Logger) LoggerFactory.getLogger("pl.aiwcorpo.mcp.audit");
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        audit.addAppender(lines);
        try {
            RawMcp raw = new RawMcp(port);
            String session = raw.openSession(GATEWAY);
            HttpResponse<String> answer = raw.post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":"
                    + "\"echo_text\",\"arguments\":{\"text\":\"a\",\"extra\":\"x\"}}}",
                    GATEWAY.user().tool("echo_text").mint().headers(), session);

            assertThat(answer.statusCode()).isEqualTo(200);
            assertThat(answer.body()).contains("invalid_argument");
            assertThat(ListedTools.CALLS.get()).isZero();
            assertThat(lines.list).anyMatch(line -> line.getFormattedMessage().contains("tool_call"));
        } finally {
            audit.detachAppender(lines);
        }
    }

    @Test
    @DisplayName("the same tool in a List<ToolCallback> bean and in a provider stops the start")
    void theSameToolInAListAndAProviderStopsTheStart() {
        Map<String, Object> properties = new HashMap<>(GATEWAY.serverProperties());
        properties.put("listbean.provider", "true");
        properties.put("spring.ai.mcp.server.name", "platform-list-bean-twice");
        assertThatThrownBy(() -> SpringTestContextStart.start(ListBeanServer.class, properties, "dev").close())
                .rootCause().hasMessageContaining("echo_text");
    }
}
