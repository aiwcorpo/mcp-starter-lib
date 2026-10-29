package pl.aiwcorpo.mcp.platform.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;

/**
 * A search query built by the server from structured conditions, never taken as text from the model. It renders what
 * {@code scripts/lib/query-rendering.mjs} of mcp-contracts renders (CC-160, {@code test-vectors/query-rendering/}):
 * <ul>
 *   <li>the conditions of the user come first, in <b>one pair of parentheses</b>, joined by {@code AND}; the scope clause of the
 *       call ({@link QueryScope}) follows after {@code AND}, so an {@code OR} inside a value cannot widen the scope;</li>
 *   <li>every value is one string literal in double quotes with {@code \} and {@code "} escaped; a control character
 *       (U+0000 to U+001F, U+007F) is refused, not escaped;</li>
 *   <li>fields and operators come from lists the contract fixes. They are <b>normative</b>: a server may allow fewer fields,
 *       never more, and a new field needs a change of the contract. The operators are {@code =}, {@code ~} and {@code !=};</li>
 *   <li>{@code ORDER BY} takes one field of the list and {@code ASC} or {@code DESC}.</li>
 * </ul>
 *
 * <pre>{@code
 * String jql = scope.jql(Set.of("text", "status"))
 *         .where("status", Operator.EQUALS, filter.status())
 *         .where("text", Operator.CONTAINS, filter.text())
 *         .orderBy("updated", Direction.DESC)
 *         .build();
 * // (status = "Open" AND text ~ "login") AND project in ("ABC", "DEF") ORDER BY updated DESC
 * }</pre>
 *
 * <p>Only conjunction exists, so no precedence can be reinterpreted, and there is no way to hand the builder a fragment of
 * query text: a value cannot close a quote or a parenthesis, call a function ({@code currentUser()}), add
 * {@code OR project = HR} or end the query early. The characters that the search engine of Jira and Confluence gives a meaning
 * to inside a text search ({@code * ? ~}) stay in the value as written (the reference rendering does not escape them); they act
 * inside that one condition only and cannot reach outside the scope. Values may not contain the characters the text hygiene of
 * the contracts forbids (CC-121).
 */
public final class StructuredQuery {

    /** What a condition compares with: the three operators of the contract. */
    public enum Operator {
        EQUALS("="),
        CONTAINS("~"),
        NOT_EQUALS("!=");

        private final String text;

        Operator(String text) {
            this.text = text;
        }
    }

    public enum Direction { ASC, DESC }

    /** The query language: the fields that conditions and {@code ORDER BY} may use in it, from the contract. */
    enum Language {

        JQL(Set.of("text", "status", "assignee", "summary"), Set.of("created", "updated", "priority", "key")),
        CQL(Set.of("text", "title", "label"), Set.of("created", "lastmodified", "title"));

        private final Set<String> conditionFields;
        private final Set<String> orderFields;

        Language(Set<String> conditionFields, Set<String> orderFields) {
            this.conditionFields = conditionFields;
            this.orderFields = orderFields;
        }
    }

    /** Fields a JQL condition may use (CC-160, normative). */
    public static final Set<String> JQL_CONDITION_FIELDS = Language.JQL.conditionFields;
    /** Fields a JQL {@code ORDER BY} may use (CC-160, normative). */
    public static final Set<String> JQL_ORDER_FIELDS = Language.JQL.orderFields;
    /** Fields a CQL condition may use (CC-160, normative). */
    public static final Set<String> CQL_CONDITION_FIELDS = Language.CQL.conditionFields;
    /** Fields a CQL {@code ORDER BY} may use (CC-160, normative). */
    public static final Set<String> CQL_ORDER_FIELDS = Language.CQL.orderFields;

    static final int MAX_VALUE_LENGTH = 1000;
    static final int MAX_CONDITIONS = 30;
    static final int MAX_QUERY_LENGTH = 8000;

    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u001f\\u007f]");

    /** Builder over the fields the tool may use and the scope clauses of the call. */
    public static final class Builder {

        private final Language language;
        private final Set<String> conditionFields;
        private final Set<String> orderFields;
        private final List<String> scope;
        private final List<String> conditions = new ArrayList<>();
        private String order;

        Builder(Language language, Set<String> conditionFields, Set<String> orderFields, List<String> scope) {
            this.language = Objects.requireNonNull(language, "language");
            this.conditionFields = requireSubset(language.conditionFields, conditionFields, "condition");
            this.orderFields = requireSubset(language.orderFields, orderFields, "ORDER BY");
            this.scope = List.copyOf(scope);
        }

        private static Set<String> requireSubset(Set<String> normative, Set<String> chosen, String what) {
            Objects.requireNonNull(chosen, "fields");
            for (String field : chosen) {
                if (!normative.contains(field)) {
                    throw new IllegalArgumentException("'" + field + "' is not a " + what + " field the contract allows " + normative
                            + " (CC-160): a server may allow fewer fields, never more");
                }
            }
            return Set.copyOf(chosen);
        }

        /**
         * Adds {@code field operator "value"}. A {@code null} value adds nothing, so optional filters of a tool can be
         * passed as they are.
         *
         * @throws IllegalArgumentException for a field that is not allowed, an empty or long value or one with characters that are
         *         not allowed (the platform reports it as {@code invalid_argument})
         */
        public Builder where(String field, Operator operator, String value) {
            if (field == null || !conditionFields.contains(field)) {
                throw new IllegalArgumentException("the field is not one this tool may filter on");
            }
            Objects.requireNonNull(operator, "operator");
            if (value == null) {
                return this;
            }
            if (conditions.size() >= MAX_CONDITIONS) {
                throw new IllegalArgumentException("more than " + MAX_CONDITIONS + " conditions");
            }
            conditions.add(field + " " + operator.text + " " + quoteValue(value));
            return this;
        }

        /**
         * The one {@code ORDER BY} of the query.
         *
         * @throws IllegalArgumentException for a field that is not allowed or a second call
         */
        public Builder orderBy(String field, Direction direction) {
            if (field == null || !orderFields.contains(field)) {
                throw new IllegalArgumentException("the field is not one this tool may sort on");
            }
            if (order != null) {
                throw new IllegalArgumentException("a query has one ORDER BY");
            }
            order = field + " " + Objects.requireNonNull(direction, "direction").name();
            return this;
        }

        /**
         * The query: the conditions in parentheses, then the scope clauses, joined by {@code AND}, then {@code ORDER BY}; empty when
         * there is nothing to say.
         */
        public String build() {
            List<String> clauses = new ArrayList<>();
            if (!conditions.isEmpty()) {
                clauses.add("(" + String.join(" AND ", conditions) + ")");
            }
            clauses.addAll(scope);
            String query = String.join(" AND ", clauses) + (order == null ? "" : " ORDER BY " + order);
            if (query.length() > MAX_QUERY_LENGTH) {
                throw new IllegalArgumentException("the query is longer than " + MAX_QUERY_LENGTH + " characters");
            }
            return query.strip();
        }

        /** The language of this builder, for messages. */
        @Override
        public String toString() {
            return "StructuredQuery.Builder[" + language + "]";
        }
    }

    private StructuredQuery() {
    }

    /**
     * A string literal for {@code value} as the reference rendering writes it: in double quotes, {@code \} and {@code "} preceded by
     * {@code \}. For identifiers of the scope, which the platform takes from the policy.
     *
     * @throws IllegalArgumentException for a value with a control character
     */
    static String quote(String value) {
        if (value == null || CONTROL.matcher(value).find()) {
            throw new IllegalArgumentException("a value must be a plain string without control characters");
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** {@link #quote} for a value of the model: also 1 to {@value #MAX_VALUE_LENGTH} characters and the text hygiene of the contracts. */
    private static String quoteValue(String value) {
        if (value.isEmpty() || value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("a value must have 1 to " + MAX_VALUE_LENGTH + " characters");
        }
        if (TextHygiene.firstProblem(value, false) != null) {
            throw new IllegalArgumentException("a value has characters that are not allowed in text");
        }
        return quote(value);
    }
}
