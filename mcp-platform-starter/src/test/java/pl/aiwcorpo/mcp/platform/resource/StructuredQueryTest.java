package pl.aiwcorpo.mcp.platform.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pl.aiwcorpo.mcp.cedar.entities.QueryConstraint;
import pl.aiwcorpo.mcp.platform.resource.StructuredQuery.Direction;
import pl.aiwcorpo.mcp.platform.resource.StructuredQuery.Operator;

/**
 * The query is built from structured filters, so no value can change its shape. The attacks of the review of beta-1
 * (an escaped quote outside quotes that moves the parentheses in Jira's lexer), backslashes, quotes, parentheses,
 * ORDER BY, comments, functions and Unicode are all values here. A lexer that reads the query the way Jira's does
 * (a backslash escapes the next character everywhere, quotes open strings) checks that the structure is the same whatever
 * the value is: the conditions in one pair of parentheses, then the scope clause, joined by AND (CC-160).
 */
class StructuredQueryTest {

    private static final Set<String> FIELDS = Set.of("status", "assignee", "summary", "text");

    private static QueryScope scope(Set<String> link) {
        QueryScope scope = QueryScope.of(new QueryConstraint("Jira::Project", new TreeSet<>(Set.of("ABC"))));
        return link == null ? scope : scope.narrowedTo(link);
    }

    /** Tokens outside string literals, with each literal as one token starting with a double quote. Backslash escapes anywhere. */
    static List<String> lex(String query) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int i = 0;
        while (i < query.length()) {
            char c = query.charAt(i);
            if (c == '\\') {
                // Jira's lexer accepts an escape outside quotes too: the next character is part of the word
                current.append(c);
                if (i + 1 < query.length()) {
                    current.append(query.charAt(++i));
                }
                i++;
            } else if (c == '"' || c == '\'') {
                flush(tokens, current);
                StringBuilder literal = new StringBuilder().append(c);
                i++;
                while (i < query.length() && query.charAt(i) != c) {
                    if (query.charAt(i) == '\\' && i + 1 < query.length()) {
                        literal.append(query.charAt(i++));
                    }
                    literal.append(query.charAt(i++));
                }
                if (i >= query.length()) {
                    throw new AssertionError("unterminated string literal in " + query);
                }
                tokens.add(literal.append(c).toString());
                i++;
            } else if (Character.isWhitespace(c)) {
                flush(tokens, current);
                i++;
            } else if (c == '(' || c == ')' || c == ',') {
                flush(tokens, current);
                tokens.add(String.valueOf(c));
                i++;
            } else {
                current.append(c);
                i++;
            }
        }
        flush(tokens, current);
        return tokens;
    }

    private static void flush(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    @Test
    void buildsTheConditionsInParenthesesThenTheScopeThenTheOrder() {
        String jql = scope(Set.of("DEF", "ABC")).jql(FIELDS)
                .where("status", Operator.EQUALS, "Open")
                .where("assignee", Operator.NOT_EQUALS, "bob")
                .where("text", Operator.CONTAINS, "login")
                .orderBy("updated", Direction.DESC).build();

        assertThat(jql).isEqualTo("(status = \"Open\" AND assignee != \"bob\" AND text ~ \"login\") AND project in (\"ABC\", \"DEF\") "
                + "ORDER BY updated DESC");
    }

    @Test
    void withoutALinkThereIsNoScopeClauseAndNothingAtAllWithoutConditions() {
        assertThat(scope(null).jql(FIELDS).where("status", Operator.NOT_EQUALS, "Done").build()).isEqualTo("(status != \"Done\")");
        assertThat(scope(null).jql(FIELDS).build()).isEmpty();
        assertThat(scope(Set.of("ABC")).jql(FIELDS).build()).isEqualTo("project in (\"ABC\")");
    }

    @Test
    void anAbsentOptionalConditionAddsNothing() {
        String jql = scope(Set.of("ABC")).jql(FIELDS).where("status", Operator.EQUALS, (String) null).build();

        assertThat(jql).isEqualTo("project in (\"ABC\")");
    }

    @Test
    void cqlGetsTheSpaceClauseAfterTheConditions() {
        QueryScope spaces = QueryScope.of(new QueryConstraint("Confluence::Page", new TreeSet<>())).narrowedTo(Set.of("ENG"));

        assertThat(spaces.cql(Set.of("title", "label")).where("title", Operator.CONTAINS, "plan").orderBy("lastmodified", Direction.ASC).build())
                .isEqualTo("(title ~ \"plan\") AND space in (\"ENG\") ORDER BY lastmodified ASC");
    }

    @Test
    void grantsAndNarrowingAreTwoClausesAfterTheConditions() {
        QueryScope both = QueryScope.of(new QueryConstraint("Jira::Project", new TreeSet<>(Set.of("ABC"))))
                .narrowedTo(Set.of("ABC", "DEF"));

        assertThat(both.jql(FIELDS, true).where("status", Operator.EQUALS, "Open").build())
                .isEqualTo("(status = \"Open\") AND project in (\"ABC\", \"DEF\") AND project in (\"ABC\")");
    }

    /** The review's payload and its relatives, as a value of every operator, in a narrowed scope. */
    @ParameterizedTest
    @ValueSource(strings = {
            "summary ~ a\\\" ) OR project = HR OR ( summary ~ \"b\\\" \"",
            "x\\\" ) OR project = HR OR ( y = \"",
            "\\", "\\\\", "\\\"", "\"", "\" OR project = HR OR \"", "'", "' OR project = HR OR '", "\\'",
            ") OR project = HR OR (", "(", ")", "a) OR (project = HR",
            " ORDER BY created", "x ORDER BY created OR project = HR", "order by 1",
            "currentUser()", "now()", "membersOf(\"hr\")", "project = HR", "1 = 1", "-- comment", "/* c */ OR /* d */", "// x", "#",
            "\u017c\u00f3\u0142\u0107", "\ud83d\ude00", "\u00e9\u0301", "\uff02 OR \uff09", "\u2018x\u2019", "\u201cx\u201d",
            "*", "?", "~", "~2", "a*b", "-HR", "+HR", "AND", "OR", "NOT", "{", "}", "[", "]", "^2", ":", "/", "&&", "||", "!"})
    void aValueIsAlwaysOneStringLiteralWhateverItContains(String hostile) {
        for (Operator operator : Operator.values()) {
            String query = scope(Set.of("ABC")).jql(FIELDS).where("summary", operator, hostile).build();
            List<String> tokens = lex(query);

            // ( summary <op> "<literal>" ) AND project in ( "ABC" )
            assertThat(tokens.get(0)).as(query).isEqualTo("(");
            assertThat(tokens.get(1)).as(query).isEqualTo("summary");
            assertThat(tokens.get(2)).as(query).isEqualTo(operator == Operator.EQUALS ? "=" : operator == Operator.CONTAINS ? "~" : "!=");
            assertThat(tokens.get(3)).as(query).startsWith("\"");
            assertThat(tokens.subList(4, tokens.size())).as(query).containsExactly(")", "AND", "project", "in", "(", "\"ABC\"", ")");
            assertThat(tokens.stream().filter(t -> t.startsWith("\"")).count()).as(query).isEqualTo(2);
        }
    }

    /** The same attacks as values of a CQL query: one string literal, the conditions in parentheses and the space clause after them. */
    @ParameterizedTest
    @ValueSource(strings = {"x\\\" ) OR space = SECRET OR ( title ~ \"y", "\\", "\"", "\" OR space = SECRET OR \"", ") OR (",
            "order by title", "currentUser()", "-- c", "/* c */", "\u00e9\u0301", "*"})
    void aCqlValueIsAlwaysOneStringLiteralToo(String hostile) {
        QueryScope spaces = QueryScope.of(new QueryConstraint("Confluence::Space", new TreeSet<>(Set.of("ENG")))).narrowedTo(Set.of("ENG"));
        for (Operator operator : Operator.values()) {
            String query = spaces.cql(Set.of("title")).where("title", operator, hostile).build();
            List<String> tokens = lex(query);

            assertThat(tokens.get(0)).as(query).isEqualTo("(");
            assertThat(tokens.get(1)).as(query).isEqualTo("title");
            assertThat(tokens.subList(4, tokens.size())).as(query).containsExactly(")", "AND", "space", "in", "(", "\"ENG\"", ")");
            assertThat(query).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
        }
    }

    @Test
    void aValueIsQuotedLikeTheReferenceRendering() {
        assertThat(StructuredQuery.quote("a\"b")).isEqualTo("\"a\\\"b\"");
        assertThat(StructuredQuery.quote("a\\b")).isEqualTo("\"a\\\\b\"");
        assertThat(StructuredQuery.quote("\u017c")).isEqualTo("\"\u017c\"");
        assertThat(StructuredQuery.quote("currentUser()")).isEqualTo("\"currentUser()\"");
        assertThat(StructuredQuery.quote("a*b?c ~ (x)")).as("Lucene characters are not escaped, as in the reference").isEqualTo("\"a*b?c ~ (x)\"");
    }

    @Test
    void aControlCharacterIsRefusedNotEscaped() {
        for (String value : new String[] {"a\nb", "a\tb", "a\rb", "a\u0000b", "a\u007fb", "a\u001fb"}) {
            assertThatThrownBy(() -> StructuredQuery.quote(value)).as(String.valueOf(value.hashCode())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> scope(null).jql(FIELDS).where("summary", Operator.CONTAINS, value)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void fieldsAndOperatorsAreTheNormativeListsOfTheContractAndAServerMayAllowFewer() {
        assertThat(StructuredQuery.JQL_CONDITION_FIELDS).containsExactlyInAnyOrder("text", "status", "assignee", "summary");
        assertThat(StructuredQuery.JQL_ORDER_FIELDS).containsExactlyInAnyOrder("created", "updated", "priority", "key");
        assertThat(StructuredQuery.CQL_CONDITION_FIELDS).containsExactlyInAnyOrder("text", "title", "label");
        assertThat(StructuredQuery.CQL_ORDER_FIELDS).containsExactlyInAnyOrder("created", "lastmodified", "title");
        assertThat(Operator.values()).extracting(Operator::name).containsExactlyInAnyOrder("EQUALS", "CONTAINS", "NOT_EQUALS");
    }

    @Test
    void aFieldBeyondTheContractIsRefusedWhenTheBuilderIsCreated() {
        QueryScope scope = scope(Set.of("ABC"));

        assertThatThrownBy(() -> scope.jql(Set.of("status", "labels"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("labels");
        assertThatThrownBy(() -> scope.jql(Set.of("cf[10000]"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.jql(Set.of("status) OR (project"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.jql(FIELDS, Set.of("summary"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(scope.jql(Set.of("status"), Set.of("created")).orderBy("created", Direction.ASC).build()).endsWith("ORDER BY created ASC");
    }

    @Test
    void aConditionOrOrderOutsideTheToolsFieldsOrASecondOrderIsRefused() {
        QueryScope scope = scope(Set.of("ABC"));

        assertThatThrownBy(() -> scope.jql(FIELDS).where("project", Operator.EQUALS, "HR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.jql(FIELDS).where("status or 1", Operator.EQUALS, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.jql(FIELDS).orderBy("project", Direction.ASC)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.jql(FIELDS).orderBy("summary ASC, project", Direction.ASC)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.jql(Set.of("status"), Set.of("created")).orderBy("updated", Direction.ASC))
                .isInstanceOf(IllegalArgumentException.class);
        StructuredQuery.Builder ordered = scope.jql(FIELDS).orderBy("created", Direction.ASC);
        assertThatThrownBy(() -> ordered.orderBy("updated", Direction.DESC)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valuesWithForbiddenCharactersOrOfTheWrongSizeAreRefused() {
        QueryScope scope = scope(null);

        for (String value : new String[] {"", "x".repeat(1001), "a\u0000b", "a\u202eb", "a\u200bb", "a\u2028b", "a\ud800b", "a\u0085b"}) {
            assertThatThrownBy(() -> scope.jql(FIELDS).where("summary", Operator.CONTAINS, value)).as(value.length() + ":" + value.hashCode())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        StructuredQuery.Builder many = scope.jql(FIELDS);
        for (int i = 0; i < 30; i++) {
            many.where("status", Operator.EQUALS, "a");
        }
        assertThatThrownBy(() -> many.where("status", Operator.EQUALS, "a")).isInstanceOf(IllegalArgumentException.class);
    }
}
