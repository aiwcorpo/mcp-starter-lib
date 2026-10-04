package pl.aiwcorpo.mcp.platform.patmode;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;

/**
 * {@code auth-mode=pat}: the server has no identity provider. A caller is whoever holds a backend
 * token, gets the single level the policy gives to {@code pat}, and that token is what reaches the
 * backend. The OAuth counterpart is {@code GuardOverTransportTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.mcp.server.protocol=STREAMABLE",
                "spring.ai.mcp.server.type=SYNC",
                "spring.ai.mcp.server.name=platform-pat-mode-test",
                "spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp/test",
                "aiwcorpo.mcp.platform.auth-mode=pat",
                "aiwcorpo.mcp.platform.guard.policy-file=classpath:pat-mode-policy.yml"
        })
@DisplayName("PAT mode over the real transport")
class PatModeOverTransportTest {

    @LocalServerPort
    int port;

    @SpringBootApplication
    static class TestServer {

        @Bean
        Wiki wiki() {
            return new Wiki();
        }

        @Bean
        ToolCallbackProvider tools(Wiki wiki) {
            return MethodToolCallbackProvider.builder().toolObjects(wiki).build();
        }
    }

    static class Wiki {

        @Tool(name = "wiki_read", description = "Reads a page.")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String read() {
            return "page";
        }

        @Tool(name = "wiki_edit", description = "Edits a page; returns the token the backend would be sent.")
        @ToolRisk(RiskLevel.UPDATE)
        public String edit() {
            return CredentialContext.requireBackendToken("wiki");
        }

        @Tool(name = "wiki_purge", description = "Removes every page.")
        @ToolRisk(RiskLevel.DESTRUCTIVE)
        public String purge() {
            return "purged";
        }
    }

    @Test
    @DisplayName("without a PAT the caller is anonymous and read-only")
    void noPatMeansAnonymous() {
        try (McpSyncClient nobody = connect(null, null)) {
            assertThat(text(call(nobody, "wiki_read"))).contains("page");
            assertThat(text(call(nobody, "wiki_edit"))).contains("access_denied");
            assertThat(text(call(nobody, "platform_whoami"))).contains("\"subject\":\"anonymous\"");
        }
    }

    @Test
    @DisplayName("a PAT holder gets the policy's pat level, and their PAT reaches the backend")
    void aPatHolderGetsThePatLevel() {
        try (McpSyncClient holder = connect("X-PAT-Wiki", "wiki-token-of-anna")) {
            assertThat(text(call(holder, "wiki_edit"))).as("policy: pat: update").contains("wiki-token-of-anna");
            assertThat(text(call(holder, "wiki_purge")))
                    .as("the pat level is update; destructive stays out of reach").contains("access_denied");

            String whoami = text(call(holder, "platform_whoami"));
            assertThat(whoami).containsPattern("\"subject\":\"pat:[0-9a-f]{12}\"");
            assertThat(whoami).as("the server cannot verify a PAT, and says so").contains("\"authenticated\":false");
            assertThat(whoami).as("the token itself is never echoed as the caller's name").doesNotContain("wiki-token-of-anna");
        }
    }

    @Test
    @DisplayName("a PAT for another system does not stand in for the one a tool needs")
    void theRightSystemsPatIsRequired() {
        try (McpSyncClient holder = connect("X-PAT-Jira", "jira-token")) {
            assertThat(text(call(holder, "wiki_edit"))).contains("missing_credential", "X-PAT-Wiki");
        }
    }

    @Test
    @DisplayName("a bearer token cannot be checked in this mode and is rejected")
    void bearerTokensAreRejected() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp/test"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Authorization", "Bearer some.jwt.value")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).get().asString()
                .as("no identity provider to point at").doesNotContain("resource_metadata");
    }

    private McpSyncClient connect(String header, String value) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port)
                .endpoint("/mcp/test")
                .httpRequestCustomizer((request, method, uri, body, context) -> {
                    if (header != null) {
                        request.header(header, value);
                    }
                })
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
    }

    private static McpSchema.CallToolResult call(McpSyncClient client, String tool) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(Map.of()).build());
    }

    private static String text(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent t) {
                text.append(t.text());
            }
        }
        return text.toString();
    }
}
