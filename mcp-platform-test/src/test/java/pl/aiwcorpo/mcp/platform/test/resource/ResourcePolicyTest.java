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
 * not there and what is not allowed look the same, a system that is down is not a denial, and results are filtered.
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

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

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
    void aCallerWithoutAGrantGetsNotFoundOrDeniedBeforeTheSystemIsAskedNotAnInternalError() {
        jira.calls.clear();
        // NOC-1 lies in a project with no category: for a caller with a grant that is an internal_error (aResourceWithoutItsClassification...), for one
        // without it the answer must not tell that the issue exists (CC-146).
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-jira-write", "mcp-jira-proj-ABC")))) {
            McpSchema.CallToolResult result = call(client, "transfer_issue", Map.of("issueKey", "NOC-1", "targetProject", "ABC"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).isEqualTo("{\"error\":\"not_found_or_denied\",\"message\":\"The resource does not exist or you do not have access to it.\"}");
            assertThat(jira.calls).as("the grant is judged before the resolver asks Jira").isEmpty();
        }
    }

    @Test
    void aCredentialTypeOtherThanTheConfiguredOneRefusesTheCallAndIsAudited() {
        jira.calls.clear();
        ch.qos.logback.classic.Logger auditLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("pl.aiwcorpo.mcp.audit");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> lines = new ch.qos.logback.core.read.ListAppender<>();
        lines.start();
        auditLog.addAppender(lines);
        try {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder("http://127.0.0.1:" + port).endpoint("/mcp").openConnectionOnStartup(false)
                    .httpRequestCustomizer(GATEWAY.asGateway(ResourcePolicyTest::reader, "Bearer test-jira-pat",
                            pl.aiwcorpo.mcp.platform.security.BackendCredential.Type.SERVICE_ACCOUNT)).build();
            try (McpSyncClient client = McpClient.sync(transport).build()) {
                client.initialize();
                McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-1"));

                assertThat(result.isError()).isTrue();
                assertThat(text(result)).contains("\"error\":\"internal_error\"");
            }
        } finally {
            auditLog.detachAppender(lines);
        }
        assertThat(lines.list).anySatisfy(line -> assertThat(line.getFormattedMessage())
                .contains("\"event\":\"policy_decision\"", "\"reason\":\"credential_type_mismatch\"", "\"argsLength\":"));
        assertThat(jira.calls).as("refused before the resolver").doesNotContain("ABC-1");
    }

    @Test
    void everyNotFoundOrDeniedAnswerIsTheSameText() {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            String missing = text(call(client, "get_issue", Map.of("issueKey", "ABC-999")));
            String restricted = text(call(client, "get_issue", Map.of("issueKey", "HR-5")));

            assertThat(missing).isEqualTo(restricted).contains("The resource does not exist or you do not have access to it.");
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
    void aResourceWithoutItsClassificationIsRefusedAsInternalError() {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "NOC-1"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"");
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
    void anInterceptorCannotSwapTheResourceAfterThePolicyJudgedIt() {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult result = call(client, "get_issue", Map.of("issueKey", "ABC-2"));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"").doesNotContain("HR-5");
        }
    }

    @Test
    void aRemovedElementLeavesAPolicyDecisionEventAndAnUnknownProjectsAreNotInIt() {
        ch.qos.logback.classic.Logger audit = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("pl.aiwcorpo.mcp.audit");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> lines = new ch.qos.logback.core.read.ListAppender<>();
        lines.start();
        audit.addAppender(lines);
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            call(client, "search_issues", Map.of("projectKeys", List.of("ABC")));

            List<String> events = pl.aiwcorpo.mcp.platform.test.CapturedLogs.snapshot(lines).stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).toList();
            assertThat(events).anySatisfy(event -> assertThat(event).contains("\"event\":\"policy_decision\"")
                    .contains("\"decision\":\"deny\"").contains("\"id\":\"HR-5\"").contains("\"errorCode\":\"not_found_or_denied\""));
            assertThat(events).noneSatisfy(event -> assertThat(event).contains("test-jira-pat"));
        } finally {
            audit.detachAppender(lines);
        }
    }

    @Test
    void theElementsOfAResultAreFetchedWithOneBulkCallNotOnePerHit() {
        jira.calls.clear();
        jira.bulkCalls.clear();
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult result = call(client, "search_issues", Map.of("projectKeys", List.of("ABC")));

            assertThat(result.isError()).isNotEqualTo(true);
            assertThat(jira.bulkCalls).hasSize(1);
            assertThat(jira.bulkCalls.getFirst()).containsExactlyInAnyOrder("ABC-1", "DEF-7", "HR-5", "ABC-2");
            assertThat(jira.calls).as("no single fetch for a hit").isEmpty();
        }
    }

    @Test
    void theLimitOnCheckedElementsCountsTheWholeResultNotEachList() {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult result = call(client, "search_two_lists", Map.of("projectKeys", List.of("ABC")));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"internal_error\"");
        }
    }

    @Test
    void aGenericPageIsFilteredLikeAnyOtherResultShape() {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult result = call(client, "search_page", Map.of("projectKeys", List.of("ABC")));

            assertThat(result.isError()).isNotEqualTo(true);
            assertThat(text(result)).contains("ABC-1", "DEF-7").doesNotContain("HR-5");
        }
    }

    @Test
    void theQueryIsBuiltByTheServerAndHostileValuesStayInsideOneLiteral() throws Exception {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            assertThat(JSON.readTree(text(call(client, "find_issues", Map.of("filter", Map.of("status", "Open"))))).asString())
                    .isEqualTo("(status = \"Open\") ORDER BY updated DESC");
            String hostile = "x\\\" ) OR project = HR OR ( text ~ \"y";
            String built = JSON.readTree(text(call(client, "find_issues", Map.of("filter", Map.of("status", hostile))))).asString();
            assertThat(built).isEqualTo("(status = \"x\\\\\\\" ) OR project = HR OR ( text ~ \\\"y\") ORDER BY updated DESC");
            assertThat(JSON.readTree(text(call(client, "find_issues", Map.of("filter", Map.of("text", "a*"))))).asString())
                    .isEqualTo("(text ~ \"a*\") ORDER BY updated DESC");
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

    @Test
    void aPagedResultLosesTheHiddenElementButKeepsTheCursorAndANextPageIsFetchedWithIt() throws Exception {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult first = call(client, "paged_issues", Map.of());

            var body = JSON.readTree(text(first));
            assertThat(body.path("items")).isEmpty();
            assertThat(body.path("nextCursor").asString()).isNotBlank();
            assertThat(text(first)).doesNotContain("HR-5");

            McpSchema.CallToolResult second = call(client, "paged_issues", Map.of("cursor", body.path("nextCursor").asString()));
            assertThat(text(second)).contains("ABC-1");
        }
    }

    @Test
    void aFilledPageDoesNotShowTheShortPageThatTheHiddenElementWouldLeave() throws Exception {
        try (McpSyncClient client = client(ResourcePolicyTest::reader)) {
            McpSchema.CallToolResult first = call(client, "filled_issues", Map.of());

            var body = JSON.readTree(text(first));
            assertThat(body.path("items")).hasSize(1);
            assertThat(text(first)).contains("ABC-1").doesNotContain("HR-5");
            assertThat(body.path("nextCursor").asString()).isNotBlank();
        }
    }

    @Test
    void aCursorOfAnotherUserIsInvalidArgumentWithTheFixedText() throws Exception {
        String cursor;
        try (McpSyncClient alice = client(ResourcePolicyTest::reader)) {
            cursor = JSON.readTree(text(call(alice, "paged_issues", Map.of()))).path("nextCursor").asString();
        }
        try (McpSyncClient other = client(user -> user.groups(List.of("mcp-jira-read")).subject("another-user"))) {
            McpSchema.CallToolResult result = call(other, "paged_issues", Map.of("cursor", cursor));

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("invalid_argument").contains("Repeat the query without a cursor");
        }
    }

    @Test
    void aToolWithAnAdditionalGroupNeedsItBesidesTheGroupOfItsRisk() {
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-jira-read")))) {
            McpSchema.CallToolResult result = call(client, "ops_report", Map.of());

            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("\"error\":\"access_denied\"");
        }
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-jira-ops")))) {
            assertThat(text(call(client, "ops_report", Map.of()))).contains("access_denied");
        }
        try (McpSyncClient client = client(user -> user.groups(List.of("mcp-jira-read", "mcp-jira-ops")))) {
            assertThat(text(call(client, "ops_report", Map.of()))).contains("report");
        }
    }
}
