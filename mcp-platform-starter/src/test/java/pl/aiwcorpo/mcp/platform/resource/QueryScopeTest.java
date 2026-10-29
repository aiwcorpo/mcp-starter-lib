package pl.aiwcorpo.mcp.platform.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.QueryConstraint;

class QueryScopeTest {

    private static QueryScope scope(String type, String linkType, Set<String> link, Set<String> granted) {
        return QueryScope.of(new QueryConstraint(type, link == null ? Optional.empty() : Optional.of(new TreeSet<>(link)),
                linkType, new TreeSet<>(granted)));
    }

    @Test
    void identifiersAreQuotedAndEscapedNeverGluedIn() {
        assertThat(QueryScope.quote("ABC")).isEqualTo("\"ABC\"");
        assertThat(QueryScope.quote("a\"b\\c")).isEqualTo("\"a\\\"b\\\\c\"");
    }

    @Test
    void theLinkNarrowingBecomesAJqlOrCqlClauseAndNoLinkMeansNoClause() {
        QueryScope narrowed = scope("Jira::Project", "Jira::Project", Set.of("DEF", "ABC"), Set.of("ABC"));
        QueryScope open = scope("Jira::Project", "Jira::Project", null, Set.of("ABC"));
        QueryScope pages = scope("Confluence::Page", "Confluence::Space", Set.of("ENG"), Set.of());

        assertThat(narrowed.jqlProjectClause()).contains("project in (\"ABC\", \"DEF\")");
        assertThat(open.jqlProjectClause()).isEmpty();
        assertThat(pages.cqlSpaceClause()).contains("space in (\"ENG\")");
        assertThat(narrowed.jqlProjectClause(true)).contains("project in (\"ABC\", \"DEF\") AND project in (\"ABC\")");
        assertThat(open.jqlProjectClause(true)).contains("project in (\"ABC\")");
    }

    @Test
    void grantsThatAreEmptyNeverTurnIntoNoFilter() {
        QueryScope none = scope("Jira::Project", "Jira::Project", null, Set.of());
        QueryScope noIndex = scope("OpenSearch::Index", "OpenSearch::Index", null, Set.of());

        assertThat(none.grantsNothing()).isTrue();
        assertThatThrownBy(() -> none.jqlProjectClause(true)).isInstanceOf(IllegalStateException.class);
        assertThat(noIndex.nothingToQuery()).isTrue();
        assertThatThrownBy(noIndex::opensearchIndexPatterns).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("every index");
        assertThatThrownBy(noIndex::opensearchIndexExpression).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aScopeThatExcludesEverythingIsAnEmptyResultForASearchAndNotFoundOrDeniedForAFetchById() {
        QueryScope noIndex = scope("OpenSearch::Index", "OpenSearch::Index", null, Set.of());
        QueryScope noProject = scope("Jira::Project", "Jira::Project", null, Set.of());
        QueryScope someProject = scope("Jira::Project", "Jira::Project", null, Set.of("ABC"));

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
        QueryScope scope = scope("OpenSearch::Index", "OpenSearch::Index", null, Set.of("logs-team-a"));
        String answer = "{\"indices\":[{\"name\":\"logs-team-a\"},{\"name\":\"logs-team-a-2\"},{\"name\":\"logs-team-ab\"},"
                + "{\"name\":\".ds-logs-team-a-000001\"},{\"name\":\"logs-team-b-1\"}],"
                + "\"aliases\":[{\"name\":\"logs-team-a-all\",\"indices\":[\"logs-team-b-1\"]}],"
                + "\"data_streams\":[{\"name\":\"logs-team-a-stream\",\"backing_indices\":[\".ds-logs-team-a-000001\"]}]}";

        assertThat(scope.resolveIndexNames(answer)).containsExactly("logs-team-a", "logs-team-a-2");
        assertThat(scope.resolveIndexArgument("logs-team-a-2", answer)).isEqualTo("logs-team-a-2");
        assertThatThrownBy(() -> scope.resolveIndexNames("{\"aliases\":[]}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.resolveIndexNames("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope("Jira::Project", "Jira::Project", null, Set.of("ABC")).resolveIndexNames(answer))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void openSearchPrefixesBecomeAPairAndNeverAWildcardOnTheName() {
        assertThat(scope("OpenSearch::Index", "OpenSearch::Index", null, Set.of("logs-team-a", "metrics"))
                .opensearchIndexPatterns()).containsExactly("logs-team-a", "logs-team-a-*", "metrics", "metrics-*");
        assertThat(scope("OpenSearch::Index", "OpenSearch::Index", null, Set.of("logs", "logs-team-a")).opensearchIndexPatterns())
                .as("a prefix inside another one is covered").containsExactly("logs", "logs-*");
    }

    @Test
    void aLinkAndTheGrantsMeetOnTheLongerPrefixOfANestedPair() {
        QueryScope scope = scope("OpenSearch::Index", "OpenSearch::Index", Set.of("logs", "audit"),
                Set.of("logs-team-a", "metrics", "audit"));

        assertThat(scope.opensearchIndexPatterns()).containsExactly("audit", "audit-*", "logs-team-a", "logs-team-a-*");
        assertThat(scope("OpenSearch::Index", "OpenSearch::Index", Set.of("logs-team-ab"), Set.of("logs-team-a"))
                .nothingToQuery()).as("logs-team-ab is not inside logs-team-a").isTrue();
        assertThat(scope("OpenSearch::Index", "OpenSearch::Index", null, Set.of("logs", "audit")).opensearchIndexExpression())
                .isEqualTo("audit,audit-*,logs,logs-*");
        assertThat(QueryScope.OPENSEARCH_SEARCH_PARAMETERS).containsEntry("expand_wildcards", "open")
                .containsEntry("allow_no_indices", "true");
    }

    @Test
    void aClauseForAnotherTypeIsAProgrammingError() {
        assertThatThrownBy(() -> scope("Confluence::Space", "Confluence::Space", null, Set.of()).jqlProjectClause())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void confluenceGrantsBecomeACqlClauseOnlyForSpacesAndNeverEmpty() {
        QueryScope spaces = scope("Confluence::Space", "Confluence::Space", Set.of("ENG"), Set.of("ENG", "OPS"));
        QueryScope open = scope("Confluence::Space", "Confluence::Space", null, Set.of("OPS"));
        QueryScope none = scope("Confluence::Space", "Confluence::Space", null, Set.of());
        QueryScope pages = scope("Confluence::Page", "Confluence::Space", null, Set.of("1000"));

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
