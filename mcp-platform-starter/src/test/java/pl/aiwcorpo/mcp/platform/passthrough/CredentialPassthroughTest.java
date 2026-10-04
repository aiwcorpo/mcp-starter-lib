package pl.aiwcorpo.mcp.platform.passthrough;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
import pl.aiwcorpo.mcp.platform.security.MissingPatException;

/**
 * A real tool call over the real MCP transport, asserting that the caller's PAT arrives.
 *
 * <h2>Why this test exists in the PLATFORM and not in a server</h2>
 * Everything about bring-your-own-token rests on one unwritten assumption: the tool handler runs on
 * the same thread that received the HTTP request, because {@link CredentialContext} is a
 * {@link ThreadLocal}. Nothing in this repository enforced that. It holds today only because Spring
 * AI's servlet stack marks its tool specifications for immediate execution; flip the transport to
 * ASYNC, or register a {@code McpSyncServerCustomizer} that forgets to repeat that setting, and
 * every tool in every server starts behaving as though no caller ever sent a token — silently, with
 * no error anywhere.
 *
 * <p>A unit test cannot see that: calling a {@code ToolCallback} directly runs it on the test's own
 * thread, where the ThreadLocal is trivially visible. Only a request that crosses the transport can
 * fail the way production would. Putting it here means one test protects the whole fleet, and it
 * breaks at platform build time — before a version bump reaches nine servers.
 *
 * <p>The second case pins the 1.0.2 fix end to end: a tool that throws {@link MissingPatException}
 * must reach the client as {@code missing_credential} naming the header, not as
 * {@code internal_error}. That path runs through Spring AI's wrapper, which is exactly what used to
 * swallow it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.mcp.server.protocol=STREAMABLE",
                "spring.ai.mcp.server.type=SYNC",
                "spring.ai.mcp.server.name=platform-passthrough-test",
                "aiwcorpo.mcp.platform.auth-mode=pat",
                "spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp/test"
        })
@DisplayName("a caller's PAT over the real transport")
class CredentialPassthroughTest {

    private static final String PAT = "cGxhdGZvcm0tc2VjcmV0";   // opaque; never parsed by anyone

    @LocalServerPort
    int port;

    @SpringBootApplication
    static class TestServer {

        // Declared as a bean rather than left to component scanning: a @Service nested inside a
        // TEST class is not a scan candidate, because its enclosing class is not one either.
        @Bean
        ProbeTools probeTools() {
            return new ProbeTools();
        }

        @Bean
        ToolCallbackProvider tools(ProbeTools probe) {
            return MethodToolCallbackProvider.builder().toolObjects(probe).build();
        }
    }

    /** Two tools: one reports what it can see, one insists on what it cannot. */
    static class ProbeTools {

        @Tool(name = "probe_credential", description = "Returns the PAT this call can see.")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String probeCredential() {
            return CredentialContext.pat("probe").orElse("NOTHING-VISIBLE");
        }

        @Tool(name = "probe_missing", description = "Always reports a missing credential header.")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String probeMissing() {
            throw new MissingPatException("probe");
        }
    }

    @Test
    @DisplayName("reaches the tool, so bring-your-own-token actually works")
    void patReachesTheToolHandler() throws Exception {
        try (McpProbe probe = McpProbe.connect(port)) {
            String body = probe.callTool("probe_credential", PAT);

            assertThat(body)
                    .as("the tool must see the caller's PAT, not an empty CredentialContext")
                    .contains(PAT)
                    .doesNotContain("NOTHING-VISIBLE");
        }
    }

    @Test
    @DisplayName("a forgotten header comes back named, not as internal_error")
    void missingCredentialSurvivesSpringAisWrapper() throws Exception {
        try (McpProbe probe = McpProbe.connect(port)) {
            String body = probe.callTool("probe_missing", PAT);

            assertThat(body).contains("missing_credential").contains("X-PAT-Probe");
            assertThat(body)
                    .as("the generic fallback means the cause chain was not walked")
                    .doesNotContain("internal_error");
        }
    }

    /** The smallest MCP client that can prove the point: handshake, then one tool call. */
    private static final class McpProbe implements AutoCloseable {

        private final HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();
        private final URI endpoint;
        private final String session;

        private McpProbe(URI endpoint, String session) {
            this.endpoint = endpoint;
            this.session = session;
        }

        static McpProbe connect(int port) throws IOException, InterruptedException {
            URI endpoint = URI.create("http://127.0.0.1:" + port + "/mcp/test");
            McpProbe probe = new McpProbe(endpoint, null);
            HttpResponse<String> initialize = probe.post("""
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                      "protocolVersion":"2025-06-18","capabilities":{},
                      "clientInfo":{"name":"platform-test","version":"1"}}}""", null, null);
            String session = initialize.headers().firstValue("Mcp-Session-Id").orElse(null);
            assertThat(session).as("the server must open a session").isNotNull();
            return new McpProbe(endpoint, session);
        }

        String callTool(String name, String pat) throws IOException, InterruptedException {
            String request = """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"%s","arguments":{}}}"""
                    .formatted(name);
            return post(request, session, pat).body();
        }

        private HttpResponse<String> post(String body, String session, String pat)
                throws IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    // Both are required by the streamable-HTTP transport; a missing Accept is a 406.
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (session != null) {
                request.header("Mcp-Session-Id", session);
            }
            if (pat != null) {
                request.header("X-PAT-Probe", pat);
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            http.close();
        }
    }
}
