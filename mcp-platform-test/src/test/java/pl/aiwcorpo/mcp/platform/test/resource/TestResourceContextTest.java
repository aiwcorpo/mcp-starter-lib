package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
import pl.aiwcorpo.mcp.platform.test.TestResourceContext;

class TestResourceContextTest {

    @Test
    void aToolMethodCalledOutsideThePlatformSaysHowToOpenAContext() {
        assertThatThrownBy(ResourceContext::required).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TestResourceContext.open");
    }

    @Test
    void theHelperOpensAContextWithWhatTheResolverWouldHaveFetched() {
        JiraServer.JiraIssue issue = new JiraServer.JiraIssue("ABC-1", "ABC", "", true);
        try (ResourceContext context = TestResourceContext.open("get_issue", Map.of("issueKey", "ABC-1"))
                .withFetched("Jira::Issue", "ABC-1", issue, null)) {
            JiraServer.IssueView view = new JiraServer.Tools(null).getIssue("ABC-1");

            assertThat(view.project()).isEqualTo("ABC");
            assertThat(context.arguments()).containsEntry("issueKey", "ABC-1");
        }
        assertThat(ResourceContext.current()).isEmpty();
    }
}
