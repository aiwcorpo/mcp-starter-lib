package pl.aiwcorpo.mcp.platform.test.customizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/** Spring AI takes one McpSyncServerCustomizer; the starter owns it and composes the server's. */
@DisplayName("the MCP server customizer is the starter's, composed with the server's")
class ServerCustomizerTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");

    @AfterAll
    static void stop() {
        GATEWAY.close();
    }

    private static SpringApplicationBuilder app(Class<?>... extra) {
        Map<String, Object> properties = new HashMap<>(GATEWAY.serverProperties());
        properties.put("server.port", "0");
        properties.put("spring.ai.mcp.server.name", "customizer-test");
        Class<?>[] sources = new Class<?>[extra.length + 1];
        sources[0] = ProbeServer.class;
        System.arraycopy(extra, 0, sources, 1, extra.length);
        return new SpringApplicationBuilder(sources).web(WebApplicationType.SERVLET).properties(properties)
                .profiles("dev");
    }

    @Test
    @DisplayName("a server customizer runs, yet tools still run on the request thread (the credential arrives)")
    void aServerCustomizerIsComposedAndCannotUndoThePlatformSettings() {
        int before = ServerCustomizers.SERVER_CUSTOMIZER_RUNS.get();
        try (ConfigurableApplicationContext context = app(ServerCustomizers.class).run()) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder("http://127.0.0.1:" + port).endpoint("/mcp").openConnectionOnStartup(false)
                    .httpRequestCustomizer(GATEWAY.asGateway(user -> { }, "Bearer composed-pat"))
                    .build();
            try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build()) {
                McpSchema.InitializeResult init = client.initialize();
                McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest
                        .builder("probe_credential").arguments(Map.of()).build());

                assertThat(ServerCustomizers.SERVER_CUSTOMIZER_RUNS.get()).isGreaterThan(before);
                assertThat(init.instructions()).isEqualTo("set by the server");
                assertThat(result.isError()).isNotEqualTo(true);
                assertThat(result.content().toString()).contains("user-secret Bearer composed-pat");
            }
        }
    }

    @Test
    @DisplayName("a @Primary customizer of the server stops the start with a pointer to the fix")
    void aPrimaryCustomizerOfTheServerIsRefused() {
        assertThatThrownBy(() -> app(PrimaryServerCustomizer.class).run().close())
                .hasMessageContaining("toolInputsCheckedByTheTools")
                .hasMessageContaining("@Primary")
                .hasMessageContaining("Remove @Primary");
    }
}
