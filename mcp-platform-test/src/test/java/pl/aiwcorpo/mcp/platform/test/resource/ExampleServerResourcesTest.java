package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/**
 * The template's scenario on a server that is none of the five systems ({@code mcp-example}): a read that is allowed, a
 * restricted resource that is refused, a search whose result is filtered, an update only inside the granted scope. The
 * resources are the fake Jira's; the groups are the server's own ({@code mcp-example-*}).
 */
@SpringBootTest(classes = JiraServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ExampleServerResourcesTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-example");

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    @AfterAll
    static void stopGateway() {
        GATEWAY.close();
    }

    @LocalServerPort
    int port;

    McpSyncClient client(Consumer<TestGateway.Token> user) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port).endpoint("/mcp").openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(user, "Bearer test-pat")).build();
        McpSyncClient client = McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    private static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
    }

    @Test
    void aReadIsAllowedARestrictedResourceIsRefusedAndASearchIsFiltered() {
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-example-read")))) {
            assertThat(text(call(client, "get_issue", Map.of("issueKey", "ABC-1")))).contains("ABC-1");
            assertThat(text(call(client, "get_issue", Map.of("issueKey", "HR-5")))).contains("not_found_or_denied");
            assertThat(text(call(client, "get_issue", Map.of("issueKey", "ABC-123")))).contains("not_found_or_denied");
            assertThat(text(call(client, "search_issues", Map.of("projectKeys", List.of("ABC")))))
                    .contains("ABC-1", "DEF-7").doesNotContain("HR-5");
        }
    }

    @Test
    void aCallerOfAnotherServersGroupsGetsNothing() {
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-jira-read")))) {
            assertThat(text(call(client, "get_issue", Map.of("issueKey", "ABC-1")))).contains("access_denied");
        }
    }

    @Test
    void anUpdateIsAllowedOnlyInsideTheGrantedScope() {
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-example-write", "mcp-jira-proj-ABC")))) {
            assertThat(text(call(client, "transfer_issue", Map.of("issueKey", "ABC-1", "targetProject", "ABC")))).contains("ABC-1");
            assertThat(text(call(client, "transfer_issue", Map.of("issueKey", "DEF-7", "targetProject", "ABC"))))
                    .contains("not_found_or_denied");
        }
    }
}
