package pl.aiwcorpo.mcp.platform.test.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.ResourceAccessException;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.guard.ToolTitle;
import pl.aiwcorpo.mcp.platform.input.ArgumentLimits;
import pl.aiwcorpo.mcp.platform.input.SchemaLimitedToolCallbacks;
import pl.aiwcorpo.mcp.platform.resource.QueryScope;
import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceRef;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;

/**
 * A Jira server over a fake Jira, for the resource checks: issues in projects of various classifications, an issue moved
 * to a restricted project, a project without a category, a system that is down.
 */
@SpringBootApplication
public class JiraServer {

    /** What Jira returns for an issue key. */
    public record JiraIssue(String key, String projectKey, String projectCategory, boolean categoryKnown) {
    }

    /** The fake system. Keys not in the map do not exist. */
    public static final class FakeJira {
        final Map<String, JiraIssue> issues = Map.of(
                "ABC-1", new JiraIssue("ABC-1", "ABC", "", true),
                "ABC-2", new JiraIssue("ABC-2", "ABC", "", true),
                "DEF-7", new JiraIssue("DEF-7", "DEF", "", true),
                "HR-5", new JiraIssue("HR-5", "HR", "Restricted", true),
                // ABC-123 was moved to HR: Jira answers the old key with the issue as it is now.
                "ABC-123", new JiraIssue("HR-45", "HR", "Restricted", true),
                "NOC-1", new JiraIssue("NOC-1", "NOC", null, false));
        final List<String> calls = new ArrayList<>();
    }

    @Bean
    FakeJira fakeJira() {
        return new FakeJira();
    }

    @Bean
    JiraIssueResolver jiraIssueResolver(FakeJira jira) {
        return new JiraIssueResolver(jira);
    }

    @Bean
    JiraProjectResolver jiraProjectResolver() {
        return new JiraProjectResolver();
    }

    @Bean
    ToolCallbackProvider tools(ResourcePolicy policy) {
        return SchemaLimitedToolCallbacks.of(new Tools(policy));
    }

    /** Resolves an issue key by asking Jira, as a real adapter would with the caller's credential. */
    public static final class JiraIssueResolver implements ResourceResolver<JiraIssue> {

        private final FakeJira jira;

        JiraIssueResolver(FakeJira jira) {
            this.jira = jira;
        }

        @Override
        public String resourceType() {
            return "Jira::Issue";
        }

        @Override
        public Optional<JiraIssue> fetch(String key) {
            jira.calls.add(key);
            if (key.startsWith("DOWN-")) {
                throw new ResourceAccessException("jira is down");
            }
            return Optional.ofNullable(jira.issues.get(key));
        }

        @Override
        public ResolvedResource describe(JiraIssue issue) {
            Map<String, Object> project = issue.categoryKnown()
                    ? Map.of("key", issue.projectKey(), "category", issue.projectCategory())
                    : Map.of("key", issue.projectKey());
            return ResolvedResource.of("Jira::Issue", issue.key(), Map.of("key", issue.key()))
                    .withParent(ResolvedResource.of("Jira::Project", issue.projectKey(), project));
        }
    }

    /** Resolves a project key; the projects of the fake Jira are ABC, DEF and the restricted HR. */
    public static final class JiraProjectResolver implements ResourceResolver<String> {

        @Override
        public String resourceType() {
            return "Jira::Project";
        }

        @Override
        public Optional<String> fetch(String key) {
            return List.of("ABC", "DEF", "HR").contains(key) ? Optional.of(key) : Optional.empty();
        }

        @Override
        public ResolvedResource describe(String key) {
            return ResolvedResource.of("Jira::Project", key, Map.of("key", key, "category", "HR".equals(key) ? "Restricted" : ""));
        }
    }

    public record IssueView(@ResourceRef(type = "Jira::Issue") String key, String project) {
    }

    public record SearchResult(List<IssueView> issues, int total) {
    }

    public record Moved(String key, String to) {
    }

    /** The tools. */
    public static final class Tools {

        private final ResourcePolicy policy;

        Tools(ResourcePolicy policy) {
            this.policy = policy;
        }

        @Tool(name = "get_issue", description = "Returns an issue by key.")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Get issue")
        public IssueView getIssue(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey")
                                  @ResourceRef(type = "Jira::Issue") String issueKey) {
            JiraIssue issue = ResourceContext.current().orElseThrow().fetched("Jira::Issue", issueKey, JiraIssue.class)
                    .orElseThrow();
            return new IssueView(issue.key(), issue.projectKey());
        }

        @Tool(name = "count_issues", description = "Counts the given issues.")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Count issues")
        public String countIssues(@ArgumentLimits(maxItems = 5, maxLength = 16, contractPattern = "JiraIssueKey")
                                  @ResourceRef(type = "Jira::Issue") List<String> issueKeys) {
            return "counted " + issueKeys.size();
        }

        @Tool(name = "search_issues", description = "Searches the given projects; links bring issues of other projects.")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Search issues")
        public SearchResult searchIssues(@ArgumentLimits(maxItems = 5, maxLength = 10, contractPattern = "JiraProjectKey")
                                         @ResourceRef(type = "Jira::Project") List<String> projectKeys) {
            List<IssueView> hits = List.of(new IssueView("ABC-1", "ABC"), new IssueView("DEF-7", "DEF"),
                    new IssueView("HR-5", "HR"), new IssueView("ABC-2", "ABC"));
            return new SearchResult(hits, hits.size());
        }

        @Tool(name = "find_issues", description = "Finds issues with a query the server limits to the allowed projects.")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Find issues")
        public String findIssues(@ArgumentLimits(maxLength = 200) String jql) {
            QueryScope scope = policy.queryScope("Jira::Project");
            return QueryScope.restrictJql(scope.jqlProjectClause(), jql);
        }

        @Tool(name = "pick_issues", description = "Returns the keys of the given issues the caller may see.")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Pick issues")
        public List<String> pickIssues(@ArgumentLimits(maxItems = 8, maxLength = 16) List<String> candidates) {
            return policy.retainAllowedById("Jira::Issue", candidates, key -> key);
        }

        @Tool(name = "transfer_issue", description = "Moves an issue to another project.")
        @ToolRisk(RiskLevel.UPDATE)
        @ToolTitle("Transfer issue")
        public Moved transferIssue(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey")
                                   @ResourceRef(type = "Jira::Issue") String issueKey,
                                   @ArgumentLimits(maxLength = 10, contractPattern = "JiraProjectKey")
                                   @ResourceRef(type = "Jira::Project") String targetProject) {
            return new Moved(issueKey, targetProject);
        }

        @Tool(name = "delete_issue", description = "Deletes an issue.")
        @ToolRisk(RiskLevel.DESTRUCTIVE)
        @ToolTitle("Delete issue")
        public String deleteIssue(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey")
                                  @ResourceRef(type = "Jira::Issue") String issueKey) {
            return "deleted " + issueKey;
        }
    }
}
