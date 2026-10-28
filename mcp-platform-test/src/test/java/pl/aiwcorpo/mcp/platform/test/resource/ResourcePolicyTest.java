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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/**
 * The resource checks over the real transport, with the example policies of the contracts as the signed bundle: every
 * value of every resource argument is evaluated (S1), a resource without its classification is refused (S2), what is
 * not there and what is not allowed look the same, a system that is down is not a denial, results are filtered, and the
 * virtual server narrowing (vsc) of the token is applied.
 */
@SpringBootTest(classes = JiraServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ResourcePolicyTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");

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

    @Autowired
    JiraServer.FakeJira jira;

    McpSyncClient client(Consumer<TestGateway.Token> user) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port).endpoint("/mcp").openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(user, "Bearer test-jira-pat")).build();
        McpSyncClient client = McpClient.sync(transport).build();
        client.initialize();
        return client;
    }

    static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
    }

    static String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private static void reader(TestGateway.Token user) {
        user.groups(List.of("mcp-jira-read"));
    }

    @Test
    void anIssueInAnOpenProjectIsReturned() {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-1"));

            assertThat(result.isError()).isNotEqualTo(true);
            assertThat(text(result)).contains("ABC-1");
        }
    }

    @Test
    void anIssueMovedToARestrictedProjectIsNotFoundOrDeniedThoughTheArgumentLooksLikeAbc() {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult moved = call(client, "get_issue", Map.of("issueKey", "ABC-123"));
            McpSchema.CallToolResult restricted = call(client, "get_issue", Map.of("issueKey", "HR-5"));
            McpSchema.CallToolResult missing = call(client, "get_issue", Map.of("issueKey", "ABC-999"));

            for (McpSchema.CallToolResult result : List.of(moved, restricted, missing)) {
                assertThat(result.isError()).isTrue();
                assertThat(text(result)).contains("\"error\":\"not_found_or_denied\"");
            }
            assertThat(text(moved)).isEqualTo(text(missing)).isEqualTo(text(restricted));
        }
    }

    @Test
    void everyValueOfAListIsEvaluatedAndOneDeniedValueEndsTheCall() {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult allowed = call(client, "count_issues", Map.of("issueKeys", List.of("ABC-1", "ABC-2", "DEF-7")));
            McpSchema.CallToolResult lastDenied = call(client, "count_issues", Map.of("issueKeys", List.of("ABC-1", "ABC-2", "HR-5")));
            McpSchema.CallToolResult middleMissing = call(client, "count_issues", Map.of("issueKeys", List.of("ABC-1", "ABC-777", "ABC-2")));

            assertThat(text(allowed)).contains("counted 3");
            assertThat(text(lastDenied)).contains("not_found_or_denied");
            assertThat(text(middleMissing)).contains("not_found_or_denied");
        }
    }

    @Test
    void aResourceWithoutItsClassificationIsDenied() {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "NOC-1"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"not_found_or_denied\"");
        }
    }

    @Test
    void aProjectUnderARestrictedGroupIsDeniedThroughItsAncestorsAtAnyDepth() {
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-jira-read", "mcp-gitlab-read")))) {
            assertThat(text(call(client, "get_project", Map.of("path", "team-a/sub/app")))).contains("project team-a/sub/app");
            assertThat(text(call(client, "get_project", Map.of("path", "solo/app")))).contains("project solo/app");
            assertThat(text(call(client, "get_project", Map.of("path", "hr/payroll/app")))).contains("not_found_or_denied");
        }
    }

    @Test
    void aGitLabProjectWithoutTheTopicsOfItsAncestorsIsDenied() {
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-jira-read", "mcp-gitlab-read")))) {
            McpSchema.CallToolResult result = call(client, "get_project", Map.of("path", "bad/app"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"not_found_or_denied\"");
        }
    }

    @Test
    void aSystemThatIsDownIsUpstreamUnavailableNotADenial() {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "DOWN-1"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"upstream_unavailable\"");
        }
    }

    @Test
    void aMalformedIdentifierIsInvalidArgumentBeforeAnyResolution() {
        jira.calls.clear();
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "abc-1"));

            assertThat(text(result)).contains("\"error\":\"invalid_argument\"");
            assertThat(jira.calls).isEmpty();
        }
    }

    @Test
    void aCallerWithoutTheGroupOfTheToolIsAccessDeniedWithoutTouchingTheSystem() {
        jira.calls.clear();
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-confluence-read")))) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-1"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"access_denied\"");
            assertThat(jira.calls).isEmpty();
        }
    }

    @Test
    void theResultIsFilteredByTheResourcesOfItsElements() {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult result = call(client, "search_issues", Map.of("projectKeys", List.of("ABC")));

            assertThat(result.isError()).isNotEqualTo(true);
            assertThat(text(result)).contains("ABC-1", "ABC-2", "DEF-7").doesNotContain("HR-5");
        }
    }

    @Test
    void anIssueOfAnotherProjectLinkedInTheSearchIsRemovedWhenTheVirtualServerNarrowsToAbc() {
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-jira-read"))
                .virtualServer("abc-only", Map.of("jiraProjects", List.of("ABC"))))) {
            McpSchema.CallToolResult result = call(client, "search_issues", Map.of("projectKeys", List.of("ABC")));

            assertThat(result.isError()).isNotEqualTo(true);
            assertThat(text(result)).contains("ABC-1", "ABC-2").doesNotContain("DEF-7").doesNotContain("HR-5");
        }
    }

    @Test
    void theVirtualServerNarrowingAlsoDeniesAnArgumentOutsideTheLink() {
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-jira-read"))
                .virtualServer("abc-only", Map.of("jiraProjects", List.of("ABC"))))) {
            assertThat(text(call(client, "get_issue", Map.of("issueKey", "ABC-1")))).contains("ABC-1");
            assertThat(text(call(client, "get_issue", Map.of("issueKey", "DEF-7")))).contains("not_found_or_denied");
            assertThat(text(call(client, "search_issues", Map.of("projectKeys", List.of("DEF"))))).contains("access_denied");
        }
    }

    @Test
    void theQueryIsLimitedToTheProjectsOfTheLinkAndLeftAloneWithoutOne() {
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-jira-read"))
                .virtualServer("two", Map.of("jiraProjects", List.of("ABC", "DEF"))))) {
            assertThat(text(call(client, "find_issues", Map.of("jql", "status = Open ORDER BY created DESC"))))
                    .isEqualTo("\"project in (\\\"ABC\\\", \\\"DEF\\\") AND (status = Open) ORDER BY created DESC\"");
        }
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            assertThat(text(call(client, "find_issues", Map.of("jql", "status = Open")))).isEqualTo("\"status = Open\"");
        }
    }

    @Test
    void aToolCanFilterItsOwnCandidatesWithTheResultFilter() {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult result = call(client, "pick_issues",
                    Map.of("candidates", List.of("ABC-1", "HR-5", "ABC-123", "ABC-404", "DEF-7", "bad key")));

            assertThat(result.isError()).as(text(result)).isNotEqualTo(true);
            assertThat(text(result)).isEqualTo("[\"ABC-1\",\"DEF-7\"]");
        }
    }

    @Test
    void anUpdateIsAllowedOnlyInsideTheGrantedProjectsOfTheSourceAndTheTarget() {
        Consumer<TestGateway.Token> writer = user -> user.groups(List.of("mcp-jira-write", "mcp-jira-proj-ABC"));
        try (McpSyncClient client = client(writer)) {
            assertThat(text(call(client, "transfer_issue", Map.of("issueKey", "ABC-1", "targetProject", "ABC"))))
                    .contains("ABC-1");
            assertThat(text(call(client, "transfer_issue", Map.of("issueKey", "ABC-1", "targetProject", "DEF"))))
                    .contains("access_denied");
            assertThat(text(call(client, "transfer_issue", Map.of("issueKey", "DEF-7", "targetProject", "ABC"))))
                    .contains("not_found_or_denied");
        }
    }

    @Test
    void aDestructiveToolIsDeniedWithoutItsGroupAndAllowedInsideTheScopeWithIt() {
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-jira-write", "mcp-jira-proj-ABC")).mfaAgeSeconds(60))) {
            assertThat(text(call(client, "delete_issue", Map.of("issueKey", "ABC-1")))).contains("access_denied");
        }
        try (McpSyncClient client = client(user -> user
                .groups(List.of("mcp-jira-destructive", "mcp-jira-proj-ABC")).mfaAgeSeconds(60))) {
            assertThat(text(call(client, "delete_issue", Map.of("issueKey", "ABC-1")))).contains("deleted ABC-1");
            assertThat(text(call(client, "delete_issue", Map.of("issueKey", "DEF-7")))).contains("not_found_or_denied");
        }
    }
}
