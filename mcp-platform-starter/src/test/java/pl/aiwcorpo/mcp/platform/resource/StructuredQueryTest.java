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
 * the value is: the scope clause, then exactly the filters that were added, joined by AND.
 */
class StructuredQueryTest {

    private static final Set<String> FIELDS = Set.of("status", "labels", "summary", "text", "created", "cf[10000]");

    private static QueryScope scope(Set<String> link) {
        return QueryScope.of(new QueryConstraint("Jira::Project", link == null ? Optional.empty() : Optional.of(new TreeSet<>(link)),
                "Jira::Project", new TreeSet<>(Set.of("ABC"))));
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
    void buildsTheScopeThenTheFiltersJoinedByAndThenTheOrder() {
        String jql = scope(Set.of("DEF", "ABC")).jql(FIELDS)
                .where("status", Operator.EQUALS, "Open")
                .where("labels", Operator.IN, List.of("a", "b c"))
                .where("created", Operator.GREATER_OR_EQUAL, "2026-01-01")
                .orderBy("created", Direction.DESC).build();

        assertThat(jql).isEqualTo("project in (\"ABC\", \"DEF\") AND status = \"Open\" AND labels in (\"a\", \"b c\") "
                + "AND created >= \"2026-01-01\" ORDER BY created DESC");
    }

    @Test
    void withoutALinkThereIsNoScopeClauseAndNothingAtAllWithoutFilters() {
        assertThat(scope(null).jql(FIELDS).where("status", Operator.NOT_EQUALS, "Done").build()).isEqualTo("status != \"Done\"");
        assertThat(scope(null).jql(FIELDS).build()).isEmpty();
        assertThat(scope(Set.of("ABC")).jql(FIELDS).build()).isEqualTo("project in (\"ABC\")");
    }

    @Test
    void absentOptionalFiltersAddNothing() {
        String jql = scope(Set.of("ABC")).jql(FIELDS).where("status", Operator.EQUALS, (String) null)
                .where("labels", Operator.IN, (List<String>) null).where("labels", Operator.IN, List.of()).build();

        assertThat(jql).isEqualTo("project in (\"ABC\")");
    }

    @Test
    void cqlGetsTheSpaceClause() {
        QueryScope spaces = QueryScope.of(new QueryConstraint("Confluence::Page", Optional.of(new TreeSet<>(Set.of("ENG"))),
                "Confluence::Space", new TreeSet<>()));

        assertThat(spaces.cql(Set.of("title", "label")).where("title", Operator.CONTAINS, "plan").build())
                .isEqualTo("space in (\"ENG\") AND title ~ \"plan\"");
    }

    /** The review's payload and its relatives, as a value of every operator, in the link-narrowed scope. */
    @ParameterizedTest
    @ValueSource(strings = {
            "summary ~ a\\\" ) OR project = HR OR ( summary ~ \"b\\\" \"",
            "x\\\" ) OR project = HR OR ( y = \"",
            "\\", "\\\\", "\\\"", "\"", "\" OR project = HR OR \"", "'", "' OR project = HR OR '", "\\'",
            ") OR project = HR OR (", "(", ")", "a) OR (project = HR",
            " ORDER BY created", "x ORDER BY created OR project = HR", "order by 1",
            "currentUser()", "now()", "membersOf(\"hr\")", "project = HR", "1 = 1", "-- comment", "/* c */ OR /* d */", "// x", "#",
            "a\nb", "a\tb", "a\r\nb", "\u017c\u00f3\u0142\u0107", "\ud83d\ude00", "\u00e9\u0301", "\uff02 OR \uff09", "\u2018x\u2019", "\u201cx\u201d",
            "*", "?", "~", "~2", "a*b", "-HR", "+HR", "AND", "OR", "NOT", "{", "}", "[", "]", "^2", ":", "/", "&&", "||", "!"})
    void aValueIsAlwaysOneStringLiteralWhateverItContains(String hostile) {
        for (Operator operator : Operator.values()) {
            String query = scope(Set.of("ABC")).jql(FIELDS).where("summary", operator, hostile).build();
            List<String> tokens = lex(query);

            // project in ( "ABC" ) AND summary <op> [ ( ] "<literal>" [ ) ]
            assertThat(tokens.subList(0, 6)).as(query).containsExactly("project", "in", "(", "\"ABC\"", ")", "AND");
            assertThat(tokens.get(6)).as(query).isEqualTo("summary");
            List<String> rest = tokens.subList(7, tokens.size());
            long literals = rest.stream().filter(t -> t.startsWith("\"")).count();
            assertThat(literals).as(query).isEqualTo(1);
            assertThat(rest.stream().filter(t -> !t.startsWith("\"")).toList()).as(query)
                    .isIn(List.of(operatorTokens(operator, false)), List.of(operatorTokens(operator, true)));
            assertThat(query).as("no raw newline in a literal").doesNotContain("\n").doesNotContain("\r");
        }
    }

    /** The same attacks as values of a CQL query: one string literal, the space clause and the field in front of it. */
    @ParameterizedTest
    @ValueSource(strings = {"x\\\" ) OR space = SECRET OR ( title ~ \"y", "\\", "\"", "\" OR space = SECRET OR \"", ") OR (", "\n",
            "order by title", "currentUser()", "-- c", "/* c */", "\u00e9\u0301", "*", "a\tb"})
    void aCqlValueIsAlwaysOneStringLiteralToo(String hostile) {
        QueryScope spaces = QueryScope.of(new QueryConstraint("Confluence::Space", Optional.of(new TreeSet<>(Set.of("ENG"))),
                "Confluence::Space", new TreeSet<>(Set.of("ENG"))));
        for (Operator operator : Operator.values()) {
            String query = spaces.cql(Set.of("title")).where("title", operator, hostile).build();
            List<String> tokens = lex(query);

            assertThat(tokens.subList(0, 6)).as(query).containsExactly("space", "in", "(", "\"ENG\"", ")", "AND");
            assertThat(tokens.get(6)).as(query).isEqualTo("title");
            assertThat(tokens.stream().filter(t -> t.startsWith("\"")).count()).as(query).isEqualTo(2);
            assertThat(query).doesNotContain("\n").doesNotContain("\r").doesNotContain("\t");
        }
    }

    private static String[] operatorTokens(Operator operator, boolean list) {
        String text = switch (operator) {
            case EQUALS -> "=";
            case NOT_EQUALS -> "!=";
            case IN -> "in";
            case NOT_IN -> "not in";
            case CONTAINS -> "~";
            case NOT_CONTAINS -> "!~";
            case GREATER -> ">";
            case GREATER_OR_EQUAL -> ">=";
            case LESS -> "<";
            case LESS_OR_EQUAL -> "<=";
        };
        List<String> tokens = new ArrayList<>(List.of(text.split(" ")));
        if (operator == Operator.IN || operator == Operator.NOT_IN) {
            tokens.add("(");
            tokens.add(")");
        }
        return tokens.toArray(String[]::new);
    }

    @Test
    void theEscapesAreTheOnesOfTheGrammar() {
        assertThat(StructuredQuery.quote("a\"b", false)).isEqualTo("\"a\\\"b\"");
        assertThat(StructuredQuery.quote("a\\b", false)).isEqualTo("\"a\\\\b\"");
        assertThat(StructuredQuery.quote("a\nb\tc\rd", false)).isEqualTo("\"a\\nb\\tc\\rd\"");
        assertThat(StructuredQuery.quote("\u017c", false)).isEqualTo("\"\u017c\"");
        assertThat(StructuredQuery.quote("currentUser()", false)).isEqualTo("\"currentUser()\"");
    }

    @Test
    void aTextSearchEscapesWhatLuceneInterpretsSoTheValueIsLiteral() {
        assertThat(StructuredQuery.quote("a*b?c", true)).isEqualTo("\"a\\\\*b\\\\?c\"");
        assertThat(StructuredQuery.quote("x~2 AND (y)", true)).isEqualTo("\"x\\\\~2 AND \\\\(y\\\\)\"");
        assertThat(StructuredQuery.quote("say \"hi\"", true)).isEqualTo("\"say \\\\\\\"hi\\\\\\\"\"");
        assertThat(StructuredQuery.quote("a\\b", true)).isEqualTo("\"a\\\\\\\\b\"");
    }

    @Test
    void aFieldOutsideTheListOrAnOperatorWithTheWrongNumberOfValuesIsRefused() {
        QueryScope scope = scope(Set.of("ABC"));

        assertThatThrownBy(() -> scope.jql(FIELDS).where("project", Operator.EQUALS, "HR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.jql(FIELDS).where("status or 1", Operator.EQUALS, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.jql(FIELDS).orderBy("project", Direction.ASC)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.jql(FIELDS).where("status", Operator.EQUALS, "a", "b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.jql(Set.of("status) OR (project"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> scope.jql(Set.of("a b"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(scope.jql(FIELDS).where("cf[10000]", Operator.EQUALS, "x").build()).endsWith("cf[10000] = \"x\"");
    }

    @Test
    void valuesWithForbiddenCharactersOrOfTheWrongSizeAreRefused() {
        QueryScope scope = scope(null);

        for (String value : new String[] {"", "x".repeat(1001), "a\u0000b", "a\u202eb", "a\u200bb", "a\u2028b", "a\ud800b", "a\u0085b"}) {
            assertThatThrownBy(() -> scope.jql(FIELDS).where("summary", Operator.CONTAINS, value)).as(value.length() + ":" + value.hashCode())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> scope.jql(FIELDS).where("labels", Operator.IN, java.util.Collections.nCopies(51, "a")))
                .isInstanceOf(IllegalArgumentException.class);
        StructuredQuery.Builder many = scope.jql(FIELDS);
        for (int i = 0; i < 30; i++) {
            many.where("status", Operator.EQUALS, "a");
        }
        assertThatThrownBy(() -> many.where("status", Operator.EQUALS, "a")).isInstanceOf(IllegalArgumentException.class);
    }
}
