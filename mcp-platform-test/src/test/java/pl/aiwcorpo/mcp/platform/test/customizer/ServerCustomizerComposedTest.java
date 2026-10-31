package pl.aiwcorpo.mcp.platform.test.customizer;

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
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/** The server's own customizer runs next to the starter's. A Spring test context, so the host (a CI container) does not matter. */
@SpringBootTest(classes = {ProbeServer.class, ServerCustomizers.class}, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.ai.mcp.server.name=customizer-test")
@ActiveProfiles("dev")
@DisplayName("the MCP server customizer is the starter's, composed with the server's")
class ServerCustomizerComposedTest {

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
    @DisplayName("a server customizer runs, yet tools still run on the request thread (the credential arrives)")
    void aServerCustomizerIsComposedAndCannotUndoThePlatformSettings() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port).endpoint("/mcp").openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(user -> { }, "Bearer composed-pat"))
                .build();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build()) {
            McpSchema.InitializeResult init = client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest
                    .builder("probe_credential").arguments(Map.of()).build());

            assertThat(ServerCustomizers.SERVER_CUSTOMIZER_RUNS.get()).isPositive();
            assertThat(init.instructions()).isEqualTo("set by the server");
            assertThat(result.isError()).isNotEqualTo(true);
            assertThat(result.content().toString()).contains("user-secret Bearer composed-pat");
        }
    }
}
