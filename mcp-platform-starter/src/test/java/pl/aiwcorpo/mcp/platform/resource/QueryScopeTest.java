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
}
