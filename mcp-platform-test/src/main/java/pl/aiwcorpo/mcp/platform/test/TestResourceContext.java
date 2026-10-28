package pl.aiwcorpo.mcp.platform.test;

import java.util.List;
import java.util.Map;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.observability.RequestIds;
import pl.aiwcorpo.mcp.platform.resource.ResourceContext;

/**
 * For a unit test that calls a {@code @Tool} method directly, outside the platform: opens the {@link ResourceContext}
 * the platform would have opened around the call, so that tool code reading {@code ResourceContext.required().fetched(...)}
 * finds what the test says the resolver fetched.
 *
 * <pre>{@code
 * try (ResourceContext context = TestResourceContext.open("get_issue", Map.of("issueKey", "ABC-1"))
 *         .withFetched("Jira::Issue", "ABC-1", new JiraIssue("ABC-1", "ABC"), null)) {
 *     assertThat(tools.getIssue("ABC-1").project()).isEqualTo("ABC");
 * }
 * }</pre>
 * The policy is not evaluated: to test the checks, call the tool through the MCP client of {@link TestGateway}.
 */
public final class TestResourceContext {

    private TestResourceContext() {
    }

    /** A context for {@code tool} called by a user in the group {@code mcp-jira-read} with {@code arguments}. */
    public static ResourceContext open(String tool, Map<String, Object> arguments) {
        CallerContext caller = new CallerContext("6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11", "jkowalski",
                List.of("mcp-jira-read"), "claude-code", null, null, tool, RequestIds.newRequestId(),
                TestGateway.DEFAULT_POLICY_REVISION, null);
        return ResourceContext.open(caller, tool, arguments);
    }
}
