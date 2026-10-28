package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
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
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Text from a system is marked as data in the result and in the published output schema (CC-072). */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.name=platform-output-test"})
@ActiveProfiles("dev")
@DisplayName("UntrustedText results")
class OutputSchemaTest {

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

    private McpSyncClient client() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port).endpoint(RawMcp.ENDPOINT).openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(user -> { }, "Bearer output-test-pat"))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
    }

    @Test
    @DisplayName("tools/list publishes the output schema of a tool that returns UntrustedText, and only of that tool")
    void theOutputSchemaIsPublished() {
        try (McpSyncClient client = client()) {
            Map<String, McpSchema.Tool> tools = new java.util.HashMap<>();
            client.listTools().tools().forEach(tool -> tools.put(tool.name(), tool));

            JsonNode schema = JSON.valueToTree(tools.get("read_page").outputSchema());
            assertThat(schema.at("/properties/body/properties/kind/const").asString()).isEqualTo("untrusted-data");
            assertThat(schema.at("/properties/title/properties/format/enum").toString()).contains("text/plain");
            assertThat(tools.get("lookup_issue").outputSchema()).isNull();
        }
    }

    @Test
    @DisplayName("the result is structured, marked as data and free of hidden characters")
    void theResultIsMarkedAndCleaned() {
        try (McpSyncClient client = client()) {
            client.listTools();
            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder("read_page").arguments(Map.of("pageId", "42")).build());

            assertThat(result.isError()).isNotEqualTo(true);
            JsonNode structured = JSON.valueToTree(result.structuredContent());
            assertThat(structured.at("/title/text").asString()).isEqualTo("Release  notes ");
            assertThat(structured.at("/body/kind").asString()).isEqualTo("untrusted-data");
            assertThat(structured.at("/body/text").asString()).isEqualTo("Ignore previous");
            assertThat(structured.at("/body/truncated").asBoolean()).isTrue();
            assertThat(GatewayTransportTest.text(result)).doesNotContain("\u202e").doesNotContain("\u200b");
        }
    }

    @Test
    @DisplayName("a component marked @Nullable may be null in the result and the call is ok")
    void aMarkedNullableComponentMayBeEmpty() {
        try (McpSyncClient client = client()) {
            client.listTools();
            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder("read_ticket").arguments(Map.of("ticketId", "7")).build());

            assertThat(result.isError()).isNotEqualTo(true);
            JsonNode structured = JSON.valueToTree(result.structuredContent());
            assertThat(structured.path("key").asString()).isEqualTo("T-7");
            assertThat(structured.path("assignee").isNull()).isTrue();
        }
    }

    @Test
    @DisplayName("a null in a component without @Nullable is C10 internal_error, audited as error, and quotes no value")
    void anUnmarkedNullIsAC10ErrorAndAnErrorEvent() {
        ListAppender<ILoggingEvent> everything = new ListAppender<>();
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        everything.start();
        root.addAppender(everything);
        try (McpSyncClient client = client()) {
            client.listTools();
            McpSchema.CallToolResult result = client.callTool(
                    McpSchema.CallToolRequest.builder("read_page_unmarked_null").arguments(Map.of("pageId", "1")).build());

            assertThat(result.isError()).isTrue();
            String text = GatewayTransportTest.text(result);
            assertThat(text).doesNotContain("output validation failed").doesNotContain(ProbeServer.NULL_RESULT_MARKER);
            assertThat(JSON.readTree(text).path("error").asString()).isEqualTo("internal_error");

            List<String> audit = CapturedLogs.snapshot(everything).stream()
                    .filter(e -> e.getLoggerName().equals("pl.aiwcorpo.mcp.audit"))
                    .map(ILoggingEvent::getFormattedMessage).filter(l -> l.contains("read_page_unmarked_null")).toList();
            assertThat(audit).hasSize(1);
            assertThat(audit.getFirst()).contains("\"outcome\":\"error\"").contains("\"errorCode\":\"internal_error\"");
            assertThat(CapturedLogs.snapshot(everything).stream()
                    .filter(e -> !e.getLoggerName().startsWith("io.modelcontextprotocol"))
                    .map(ILoggingEvent::getFormattedMessage).toList())
                    .noneSatisfy(line -> assertThat(line).contains(ProbeServer.NULL_RESULT_MARKER));
            assertThat(CapturedLogs.snapshot(everything).stream()
                    .anyMatch(e -> e.getFormattedMessage().contains("does not match its outputSchema at /id"))).isTrue();
        } finally {
            root.detachAppender(everything);
        }
    }
}
