package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
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
            assertThat(structured.at("/title/text").asString()).isEqualTo("Release notes");
            assertThat(structured.at("/body/kind").asString()).isEqualTo("untrusted-data");
            assertThat(structured.at("/body/text").asString()).isEqualTo("Ignore previous inst");
            assertThat(structured.at("/body/truncated").asBoolean()).isTrue();
            assertThat(GatewayTransportTest.text(result)).doesNotContain("\u202e").doesNotContain("\u200b");
        }
    }
}
