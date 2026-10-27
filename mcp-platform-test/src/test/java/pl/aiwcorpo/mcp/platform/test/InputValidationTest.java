package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;
import pl.aiwcorpo.mcp.platform.test.customizer.ServerCustomizers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Argument violations over the real transport (Tomcat + Spring AI streamable HTTP): the starter answers with a
 * C10 {@code invalid_argument} and writes the C8 event; the MCP SDK's own text never appears, even though a
 * server customizer tries to switch the SDK validation back on; the offending value reaches neither the
 * response nor any log line.
 */
@SpringBootTest(classes = {ProbeServer.class, ServerCustomizers.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.name=platform-input-test"})
@ActiveProfiles("dev")
@DisplayName("tool arguments are validated by the starter")
class InputValidationTest {

    private static final String MARKER = "leak-marker-7c2e41";
    private static final JsonMapper JSON = JsonMapper.builder().build();

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

    private final ListAppender<ILoggingEvent> everything = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private Level previous;

    @BeforeEach
    void captureAllLogs() {
        previous = root.getLevel();
        root.setLevel(Level.DEBUG);
        everything.start();
        root.addAppender(everything);
    }

    @AfterEach
    void restore() {
        root.detachAppender(everything);
        root.setLevel(previous);
    }

    private McpSchema.CallToolResult call(String tool, Map<String, Object> arguments) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port).endpoint(RawMcp.ENDPOINT).openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(user -> { }, "Bearer input-test-pat"))
                .build();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build()) {
            client.initialize();
            return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
        }
    }

    private static JsonNode error(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isTrue();
        String text = GatewayTransportTest.text(result);
        assertThat(text).doesNotContain("input validation failed");
        JsonNode c10 = JSON.readTree(text);
        assertThat(c10.path("error").asString()).isEqualTo("invalid_argument");
        return c10;
    }

    private List<String> logLines() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : List.copyOf(everything.list)) {
            // The SDK's DEBUG wire logging (client and server share one JVM here) prints every message by
            // design; this checks what the platform and Spring write.
            if (event.getLoggerName().startsWith("io.modelcontextprotocol")) {
                continue;
            }
            lines.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                lines.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            event.getMDCPropertyMap().values().forEach(lines::add);
        }
        return lines;
    }

    private static Map<String, Object> args(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("limits are published in the inputSchema the client sees")
    void theSchemaCarriesTheLimits() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port).endpoint(RawMcp.ENDPOINT).openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(user -> { }, null)).build();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build()) {
            client.initialize();
            McpSchema.Tool tool = client.listTools().tools().stream()
                    .filter(t -> t.name().equals("lookup_issue")).findFirst().orElseThrow();
            JsonNode schema = JSON.valueToTree(tool.inputSchema());
            assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
            assertThat(schema.path("properties").path("issueKey").path("maxLength").asInt()).isEqualTo(16);
            assertThat(schema.path("properties").path("issueKey").path("pattern").asString()).isEqualTo("^[A-Z]+-[0-9]+$");
            assertThat(schema.path("properties").path("limit").path("maximum").asInt()).isEqualTo(100);
        }
    }

    @Test
    @DisplayName("a valid call runs")
    void aValidCallRuns() {
        McpSchema.CallToolResult result = call("lookup_issue", args("issueKey", "ABC-12", "limit", 10));

        assertThat(result.isError()).isNotEqualTo(true);
        assertThat(GatewayTransportTest.text(result)).contains("found ABC-12");
    }

    @Test
    @DisplayName("pattern, length, type, range and unknown argument: C10 invalid_argument without the value")
    void violationsAreC10WithoutTheValue() {
        String patternMessage = error(call("lookup_issue", args("issueKey", "lower-" + MARKER))).path("message").asString();
        String lengthMessage = error(call("lookup_issue", args("issueKey", "ABC-" + "9".repeat(40) + MARKER))).path("message").asString();
        String typeMessage = error(call("lookup_issue", args("issueKey", "ABC-1", "limit", MARKER))).path("message").asString();
        String rangeMessage = error(call("lookup_issue", args("issueKey", "ABC-1", "limit", 1000))).path("message").asString();
        String unknownMessage = error(call("lookup_issue", args("issueKey", "ABC-1", MARKER, "x"))).path("message").asString();
        String missingMessage = error(call("lookup_issue", args())).path("message").asString();

        assertThat(patternMessage).contains("issueKey does not match the required format");
        assertThat(lengthMessage).contains("issueKey is longer than 16 characters");
        assertThat(typeMessage).contains("limit has the wrong type");
        assertThat(rangeMessage).contains("limit is above the maximum 100");
        assertThat(unknownMessage).contains("unknown arguments are not allowed");
        assertThat(missingMessage).contains("issueKey is required");
        assertThat(List.of(patternMessage, lengthMessage, typeMessage, rangeMessage, unknownMessage, missingMessage))
                .allSatisfy(message -> assertThat(message).doesNotContain(MARKER).doesNotContain("lower-"));
    }

    @Test
    @DisplayName("the violation is audited (C8) and neither the value nor the unknown name is in any log line")
    void theViolationIsAuditedAndNothingLeaks() {
        error(call("lookup_issue", args("issueKey", "lower-" + MARKER, MARKER + "-name", MARKER)));

        List<String> audit = everything.list.stream().filter(e -> e.getLoggerName().equals("pl.aiwcorpo.mcp.audit"))
                .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(audit).anySatisfy(line -> {
            JsonNode event = JSON.readTree(line);
            assertThat(event.path("event").asString()).isEqualTo("tool_call");
            assertThat(event.path("tool").asString()).isEqualTo("lookup_issue");
            assertThat(event.path("outcome").asString()).isEqualTo("error");
            assertThat(event.path("errorCode").asString()).isEqualTo("invalid_argument");
            assertThat(event.path("credentialType").asString()).isEqualTo("user-secret");
            assertThat(event.path("argsLength").asInt()).isPositive();
            assertThat(event.path("sub").asString()).isNotBlank();
        });
        assertThat(logLines()).allSatisfy(line -> assertThat(line).doesNotContain(MARKER).doesNotContain("input-test-pat"));
    }
}
