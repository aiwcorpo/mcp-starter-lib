package pl.aiwcorpo.mcp.platform.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;

/**
 * A search query built by the server from structured filters, never taken as text from the model: fields from a list the
 * tool author gives, operators from a closed set, values that are always quoted and escaped here according to the grammar
 * of JQL (Jira) or CQL (Confluence), and the scope of the call ({@link QueryScope}) put in front by the platform. There is
 * no way to hand the builder a fragment of query text, so a value cannot close a quote or a parenthesis, call a function
 * ({@code currentUser()}), add {@code OR project = HR} or end the query early.
 *
 * <pre>{@code
 * String jql = scope.jql(Set.of("status", "assignee", "labels", "created"))
 *         .where("status", Operator.EQUALS, filter.status())
 *         .where("labels", Operator.IN, filter.labels())
 *         .orderBy("created", Direction.DESC)
 *         .build();
 * // project in ("ABC", "DEF") AND status = "Open" AND labels in ("a", "b") ORDER BY created DESC
 * }</pre>
 *
 * <p>Only conjunction ({@code AND}) exists, so no precedence can be reinterpreted. A free text search is
 * {@link Operator#CONTAINS} on {@code text} (or {@code summary}, {@code title}, ...); the characters Lucene gives a meaning
 * to ({@code * ? " ~ ( ) [ ] { } + - ! ^ : / & | \}) are escaped, so the value is literal text and not a wildcard or
 * proximity expression. Values may not contain the characters the text hygiene of the contracts forbids (CC-121).
 */
public final class StructuredQuery {

    /** What a filter compares with. */
    public enum Operator {
        EQUALS("=", false),
        NOT_EQUALS("!=", false),
        IN("in", true),
        NOT_IN("not in", true),
        CONTAINS("~", false),
        NOT_CONTAINS("!~", false),
        GREATER(">", false),
        GREATER_OR_EQUAL(">=", false),
        LESS("<", false),
        LESS_OR_EQUAL("<=", false);

        private final String text;
        private final boolean list;

        Operator(String text, boolean list) {
            this.text = text;
            this.list = list;
        }
    }

    public enum Direction { ASC, DESC }

    static final int MAX_VALUE_LENGTH = 1000;
    static final int MAX_VALUES = 50;
    static final int MAX_FILTERS = 30;
    static final int MAX_QUERY_LENGTH = 8000;

    private static final Pattern FIELD = Pattern.compile("^(?:[A-Za-z][A-Za-z0-9_.]{0,63}|cf\\[[0-9]{1,9}\\])$");
    private static final String LUCENE_SPECIAL = "+-&|!(){}[]^\"~*?:\\/";

    /** Builder over the fields the tool may filter on and the scope clause of the call. */
    public static final class Builder {

        private final Set<String> allowedFields;
        private final Optional<String> scope;
        private final List<String> filters = new ArrayList<>();
        private final List<String> order = new ArrayList<>();

        Builder(Set<String> allowedFields, Optional<String> scope) {
            Objects.requireNonNull(allowedFields, "allowedFields");
            for (String field : allowedFields) {
                if (!FIELD.matcher(field).matches()) {
                    throw new IllegalArgumentException("'" + field + "' is not a field name that can be filtered on "
                            + "(letters, digits, '_' and '.', or cf[n])");
                }
            }
            this.allowedFields = Set.copyOf(allowedFields);
            this.scope = Objects.requireNonNull(scope, "scope");
        }

        /**
         * Adds {@code field operator values}. A value that is {@code null} or an empty list adds nothing, so optional
         * filters of a tool can be passed as they are.
         *
         * @param values one value for the comparison operators, one to {@value StructuredQuery#MAX_VALUES} for
         *               {@code IN} and {@code NOT_IN}
         * @throws IllegalArgumentException for a field that is not allowed, a value with forbidden characters, too long or too
         *         many values (the platform reports it as {@code invalid_argument})
         */
        public Builder where(String field, Operator operator, String... values) {
            return where(field, operator, values == null ? null : java.util.Arrays.asList(values));
        }

        public Builder where(String field, Operator operator, List<String> values) {
            requireAllowed(field);
            Objects.requireNonNull(operator, "operator");
            if (values == null || values.isEmpty() || (values.size() == 1 && values.getFirst() == null)) {
                return this;
            }
            if (!operator.list && values.size() != 1) {
                throw new IllegalArgumentException(operator.text + " takes one value");
            }
            if (values.size() > MAX_VALUES) {
                throw new IllegalArgumentException("more than " + MAX_VALUES + " values");
            }
            if (filters.size() >= MAX_FILTERS) {
                throw new IllegalArgumentException("more than " + MAX_FILTERS + " filters");
            }
            boolean text = operator == Operator.CONTAINS || operator == Operator.NOT_CONTAINS;
            List<String> quoted = new ArrayList<>();
            for (String value : values) {
                quoted.add(quote(value, text));
            }
            filters.add(operator.list ? field + " " + operator.text + " (" + String.join(", ", quoted) + ")"
                    : field + " " + operator.text + " " + quoted.getFirst());
            return this;
        }

        public Builder orderBy(String field, Direction direction) {
            requireAllowed(field);
            order.add(field + " " + Objects.requireNonNull(direction, "direction").name());
            return this;
        }

        /** The query: scope clause and filters joined by {@code AND}, then {@code ORDER BY}; empty when there is nothing to say. */
        public String build() {
            List<String> clauses = new ArrayList<>();
            scope.ifPresent(clauses::add);
            clauses.addAll(filters);
            String query = String.join(" AND ", clauses) + (order.isEmpty() ? "" : " ORDER BY " + String.join(", ", order));
            if (query.length() > MAX_QUERY_LENGTH) {
                throw new IllegalArgumentException("the query is longer than " + MAX_QUERY_LENGTH + " characters");
            }
            return query.strip();
        }

        private void requireAllowed(String field) {
            if (field == null || !allowedFields.contains(field)) {
                throw new IllegalArgumentException("the field is not one this tool may filter on");
            }
        }
    }

    private StructuredQuery() {
    }

    /**
     * A JQL/CQL string literal for {@code value}: in double quotes, with the backslash, the double quote and the line
     * breaks escaped; for a text search ({@code text}) the characters Lucene interprets are escaped first.
     *
     * @throws IllegalArgumentException for an empty or long value or one with characters the text hygiene forbids
     */
    static String quote(String value, boolean text) {
        if (value == null || value.isEmpty() || value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("a value must have 1 to " + MAX_VALUE_LENGTH + " characters");
        }
        if (TextHygiene.firstProblem(value, false) != null) {
            throw new IllegalArgumentException("a value has characters that are not allowed in text");
        }
        StringBuilder literal = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (text && LUCENE_SPECIAL.indexOf(c) >= 0) {
                literal.append("\\\\");
                if (c == '\\') {
                    literal.append("\\\\");
                    continue;
                }
                if (c == '"') {
                    literal.append("\\\"");
                    continue;
                }
            }
            switch (c) {
                case '\\' -> literal.append("\\\\");
                case '"' -> literal.append("\\\"");
                case '\n' -> literal.append("\\n");
                case '\r' -> literal.append("\\r");
                case '\t' -> literal.append("\\t");
                default -> literal.append(c);
            }
        }
        return literal.append('"').toString();
    }
}
