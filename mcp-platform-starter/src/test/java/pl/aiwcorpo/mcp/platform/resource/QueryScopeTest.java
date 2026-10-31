package pl.aiwcorpo.mcp.platform.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.QueryConstraint;

class QueryScopeTest {

    private static QueryScope scope(String type, Set<String> narrowing, Set<String> granted) {
        QueryScope scope = QueryScope.of(new QueryConstraint(type, new TreeSet<>(granted)));
        return narrowing == null ? scope : scope.narrowedTo(narrowing);
    }

    @Test
    void identifiersAreQuotedAndEscapedNeverGluedIn() {
        assertThat(QueryScope.quote("ABC")).isEqualTo("\"ABC\"");
        assertThat(QueryScope.quote("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
    }

    @Test
    void aNarrowingBecomesAJqlOrCqlClauseAndNoNarrowingMeansNoClause() {
        QueryScope narrowed = scope("Jira::Project", Set.of("DEF", "ABC"), Set.of("ABC"));
        QueryScope open = scope("Jira::Project", null, Set.of("ABC"));
        QueryScope pages = scope("Confluence::Page", Set.of("ENG"), Set.of());

        assertThat(narrowed.jqlProjectClause()).contains("project in (\"ABC\", \"DEF\")");
        assertThat(open.jqlProjectClause()).isEmpty();
        assertThat(pages.cqlSpaceClause()).contains("space in (\"ENG\")");
        assertThat(narrowed.jqlProjectClause(true)).contains("project in (\"ABC\", \"DEF\") AND project in (\"ABC\")");
        assertThat(open.jqlProjectClause(true)).contains("project in (\"ABC\")");
    }

    @Test
    void grantsThatAreEmptyNeverTurnIntoNoFilter() {
        QueryScope none = scope("Jira::Project", null, Set.of());
        QueryScope noIndex = scope("OpenSearch::Index", null, Set.of());

        assertThat(none.grantsNothing()).isTrue();
        assertThatThrownBy(() -> none.jqlProjectClause(true)).isInstanceOf(IllegalStateException.class);
        assertThat(noIndex.nothingToQuery()).isTrue();
        assertThatThrownBy(noIndex::opensearchIndexPatterns).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("every index");
        assertThatThrownBy(noIndex::opensearchIndexExpression).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aScopeThatExcludesEverythingIsAnEmptyResultForASearchAndNotFoundOrDeniedForAFetchById() {
        QueryScope noIndex = scope("OpenSearch::Index", null, Set.of());
        QueryScope noProject = scope("Jira::Project", null, Set.of());
        QueryScope someProject = scope("Jira::Project", null, Set.of("ABC"));

        assertThat(noIndex.nothingToQuery()).isTrue();
        assertThat(noProject.nothingToQuery()).isTrue();
        assertThat(someProject.nothingToQuery()).isFalse();
        assertThat(noIndex.resolveIndexNames("{\"indices\":[{\"name\":\"logs-team-a-1\"}]}")).isEmpty();
        assertThatThrownBy(noIndex::requireQueryable).isInstanceOf(pl.aiwcorpo.mcp.platform.error.SanitizedToolException.class)
                .extracting(error -> ((pl.aiwcorpo.mcp.platform.error.SanitizedToolException) error).toSafeError())
                .isEqualTo(new pl.aiwcorpo.mcp.platform.error.McpErrorMapper.SafeError("not_found_or_denied",
                        "The resource does not exist or you do not have access to it."));
        assertThatThrownBy(() -> noIndex.resolveIndexArgument("logs-team-a-1", "{\"indices\":[{\"name\":\"logs-team-a-1\"}]}"))
                .as("nothing to query: the same answer as for an index the user cannot see")
                .isInstanceOf(pl.aiwcorpo.mcp.platform.error.SanitizedToolException.class);
        someProject.requireQueryable();
    }

    @Test
    void indexNamesComeOnlyFromTheIndicesOfTheAnswerAndOnlyInsideTheScope() {
        QueryScope scope = scope("OpenSearch::Index", null, Set.of("logs-team-a"));
        String answer = "{\"indices\":[{\"name\":\"logs-team-a\"},{\"name\":\"logs-team-a-2\"},{\"name\":\"logs-team-ab\"},"
                + "{\"name\":\".ds-logs-team-a-000001\"},{\"name\":\"logs-team-b-1\"}],"
                + "\"aliases\":[{\"name\":\"logs-team-a-all\",\"indices\":[\"logs-team-b-1\"]}],"
                + "\"data_streams\":[{\"name\":\"logs-team-a-stream\",\"backing_indices\":[\".ds-logs-team-a-000001\"]}]}";

        assertThat(scope.resolveIndexNames(answer)).containsExactly("logs-team-a", "logs-team-a-2");
        assertThat(scope.resolveIndexArgument("logs-team-a-2", answer)).isEqualTo("logs-team-a-2");
        assertThatThrownBy(() -> scope.resolveIndexNames("{\"aliases\":[]}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.resolveIndexNames("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope("Jira::Project", null, Set.of("ABC")).resolveIndexNames(answer))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void thereIsNoEmptySearchTargetAndAnIndexNameThePatternRefusesIsDropped() {
        QueryScope scope = scope("OpenSearch::Index", null, Set.of("logs-team-a"));
        String none = "{\"indices\":[{\"name\":\"logs-team-b-1\"}],\"aliases\":[],\"data_streams\":[]}";
        String hostile = "{\"indices\":[{\"name\":\"logs-team-a-%2clogs-team-b-1\"},{\"name\":\"logs-team-a-1\"},{\"name\":\"logs-team-a-x=y\"}]}";

        assertThat(scope.opensearchSearchTarget(none)).as("no index of the scope exists: nothing to query, never an empty target").isEmpty();
        assertThat(scope.opensearchSearchTarget(QueryScope.EMPTY_RESOLVE_RESPONSE)).isEmpty();
        assertThat(scope.opensearchSearchTarget(hostile)).contains("logs-team-a-1");
        assertThat(scope("OpenSearch::Index", null, Set.of()).opensearchSearchTarget(hostile)).isEmpty();
        assertThat(scope.opensearchSearchTarget("{\"indices\":[{\"name\":\"logs-team-a-2\"},{\"name\":\"logs-team-a\"}]}"))
                .contains("logs-team-a,logs-team-a-2");
    }

    @Test
    void theResolveRequestIgnoresAMissingConcreteNameAndAScopeWithOnlyDatedIndicesStillHasATarget() {
        assertThat(QueryScope.OPENSEARCH_RESOLVE_PARAMETERS).containsEntry("ignore_unavailable", "true")
                .containsEntry("expand_wildcards", "open").containsEntry("allow_no_indices", "true");
        QueryScope scope = scope("OpenSearch::Index", null, Set.of("logs-team-a"));
        // the answer for logs-team-a,logs-team-a-* when there is no index named logs-team-a, only the dated ones
        String answer = "{\"indices\":[{\"name\":\"logs-team-a-2026.10\"},{\"name\":\"logs-team-a-2026.11\"}],\"aliases\":[],\"data_streams\":[]}";

        assertThat(scope.opensearchSearchTarget(answer)).contains("logs-team-a-2026.10,logs-team-a-2026.11");
    }

    @Test
    void anArgumentNarrowsTheScopeAfterTheConditionsAndNeverWidensIt() {
        QueryScope open = scope("Jira::Project", null, Set.of("ABC", "DEF"));
        QueryScope narrowed = scope("Jira::Project", Set.of("ABC", "DEF"), Set.of("ABC", "DEF"));
        Set<String> fields = Set.of("text", "status");

        assertThat(open.narrowedTo(List.of("DEF")).jql(fields).where("text", StructuredQuery.Operator.CONTAINS, "x").build())
                .isEqualTo("(text ~ \"x\") AND project in (\"DEF\")");
        assertThat(narrowed.narrowedTo(List.of("ABC", "ZZZ")).jql(fields).build()).as("ZZZ is outside the earlier narrowing and drops out")
                .isEqualTo("project in (\"ABC\")");
        assertThat(narrowed.narrowedTo(List.of("ABC", "DEF")).jql(fields, true).build())
                .isEqualTo("project in (\"ABC\", \"DEF\") AND project in (\"ABC\", \"DEF\")");
        assertThat(narrowed.narrowedTo(List.of("ABC")).narrowedByArgument()).isTrue();
        assertThat(open.narrowedByArgument()).isFalse();
    }

    @Test
    void anArgumentOutsideTheNarrowingOrTheGrantsLeavesNothingToQuery() {
        QueryScope narrowed = scope("Jira::Project", Set.of("ABC"), Set.of("ABC", "HR"));
        QueryScope outside = narrowed.narrowedTo(List.of("HR"));

        assertThat(outside.nothingToQuery()).isTrue();
        assertThat(narrowed.narrowedTo(List.of()).nothingToQuery()).isTrue();
        assertThatThrownBy(() -> outside.jql(Set.of("text")).build()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(outside::requireQueryable).isInstanceOf(pl.aiwcorpo.mcp.platform.error.SanitizedToolException.class);

        QueryScope notGranted = scope("Jira::Project", null, Set.of("ABC")).narrowedTo(List.of("HR"));
        assertThat(notGranted.jql(Set.of("text")).build()).as("the user's own rights apply to the system's side").isEqualTo("project in (\"HR\")");
        assertThatThrownBy(() -> notGranted.jql(Set.of("text"), true)).as("but a query limited by grants is empty").isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anArgumentIsAValidContainerIdentifierAndNotQueryText() {
        QueryScope scope = scope("Confluence::Page", null, Set.of());

        assertThatThrownBy(() -> scope.narrowedTo(List.of("ENG\" OR space = \"HR"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(scope.narrowedTo(List.of("ENG")).cql(Set.of("title")).where("title", StructuredQuery.Operator.EQUALS, "x").build())
                .isEqualTo("(title = \"x\") AND space in (\"ENG\")");
        assertThatThrownBy(() -> scope("OpenSearch::Index", null, Set.of("a")).narrowedTo(List.of("a")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void openSearchPrefixesBecomeAPairAndNeverAWildcardOnTheName() {
        assertThat(scope("OpenSearch::Index", null, Set.of("logs-team-a", "metrics"))
                .opensearchIndexPatterns()).containsExactly("logs-team-a", "logs-team-a-*", "metrics", "metrics-*");
        assertThat(scope("OpenSearch::Index", null, Set.of("logs", "logs-team-a")).opensearchIndexPatterns())
                .as("a prefix inside another one is covered").containsExactly("logs", "logs-*");
    }

    @Test
    void openSearchIsNeverNarrowedByAnArgument() {
        assertThat(QueryScope.OPENSEARCH_SEARCH_PARAMETERS).containsEntry("expand_wildcards", "open")
                .containsEntry("allow_no_indices", "true");
        assertThat(scope("OpenSearch::Index", null, Set.of("logs", "audit")).opensearchIndexExpression())
                .isEqualTo("audit,audit-*,logs,logs-*");
    }

    @Test
    void aClauseForAnotherTypeIsAProgrammingError() {
        assertThatThrownBy(() -> scope("Confluence::Space", null, Set.of()).jqlProjectClause())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void confluenceGrantsBecomeACqlClauseOnlyForSpacesAndNeverEmpty() {
        QueryScope spaces = scope("Confluence::Space", Set.of("ENG"), Set.of("ENG", "OPS"));
        QueryScope open = scope("Confluence::Space", null, Set.of("OPS"));
        QueryScope none = scope("Confluence::Space", null, Set.of());
        QueryScope pages = scope("Confluence::Page", null, Set.of("1000"));

        assertThat(spaces.cqlSpaceClause(true)).contains("space in (\"ENG\") AND space in (\"ENG\", \"OPS\")");
        assertThat(open.cqlSpaceClause(true)).contains("space in (\"OPS\")");
        assertThat(open.cqlSpaceClause(false)).isEmpty();
        assertThatThrownBy(() -> none.cqlSpaceClause(true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> pages.cqlSpaceClause(true)).isInstanceOf(IllegalStateException.class).hasMessageContaining("subtrees");
        assertThat(open.cql(Set.of("title"), true).build()).isEqualTo("space in (\"OPS\")");
    }

    @Test
    void anAggregateOverAnAliasOrADataStreamIsRefused() {
        QueryScope.requireNoAliases("{\"indices\":[{\"name\":\"logs-team-a-1\"}],\"aliases\":[],\"data_streams\":[]}");
        for (String answer : new String[] {
                "{\"indices\":[],\"aliases\":[{\"name\":\"logs-team-a-all\",\"indices\":[\"hr-1\"]}],\"data_streams\":[]}",
                "{\"indices\":[],\"aliases\":[],\"data_streams\":[{\"name\":\"logs-team-a-ds\"}]}"}) {
            assertThatThrownBy(() -> QueryScope.requireNoAliases(answer))
                    .isInstanceOf(pl.aiwcorpo.mcp.platform.error.SanitizedToolException.class)
                    .satisfies(ex -> assertThat(((pl.aiwcorpo.mcp.platform.error.SanitizedToolException) ex).getCode()).isEqualTo("access_denied"));
        }
        assertThatThrownBy(() -> QueryScope.requireNoAliases("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QueryScope.requireNoAliases("{\"hits\":1}")).isInstanceOf(IllegalArgumentException.class);
    }
}
