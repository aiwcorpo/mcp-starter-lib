package pl.aiwcorpo.mcp.platform.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import pl.aiwcorpo.mcp.cedar.entities.QueryConstraint;

/**
 * What a search or list query must be limited to for one resource type (cedar/entity-builder.md, section 8, CC-036),
 * and the clauses to put in the query of the system. Obtained from {@link ResourcePolicy#queryScope}. It saves traffic
 * and does not reveal how many elements the caller may not see; it does not replace {@link ResultFilter}, which is the
 * control.
 *
 * <ul>
 *   <li>{@link #linkIds()}: the virtual server (link) the call came through narrows this type to these ids. Always put
 *       into the query ({@link #jqlProjectClause()}, {@link #cqlSpaceClause()}). Empty = no narrowing.</li>
 *   <li>{@link #grantedIds()}: what the groups of the caller grant, from the scope mapping of the bundle. A system that
 *       applies the user's own rights (Jira, Confluence, GitLab with the user's token) needs no clause for it; where it
 *       does not (OpenSearch, a service account) the query is limited to it, and <b>an empty set means the query is not
 *       run</b> ({@link #grantsNothing()}), never "no filter".</li>
 *   <li>Identifiers match the per-system patterns of the contracts and are still quoted ({@link #quote}): never glue them
 *       into a query without quotes.</li>
 * </ul>
 */
public final class QueryScope {

    private final String resourceType;
    private final Optional<SortedSet<String>> linkIds;
    private final String linkType;
    private final SortedSet<String> grantedIds;

    private QueryScope(QueryConstraint constraint) {
        this.resourceType = constraint.resourceType();
        this.linkIds = constraint.virtualServer();
        this.linkType = constraint.virtualServerType();
        this.grantedIds = constraint.granted();
    }

    public static QueryScope of(QueryConstraint constraint) {
        return new QueryScope(Objects.requireNonNull(constraint, "constraint"));
    }

    /** The type asked for, for example {@code Jira::Project}. */
    public String resourceType() {
        return resourceType;
    }

    /** The ids the virtual server narrows to, or empty when the call is not narrowed for this type. */
    public Optional<SortedSet<String>> linkIds() {
        return linkIds;
    }

    /** The type of {@link #linkIds()}: the type itself, or {@code Confluence::Space} for pages. */
    public String linkType() {
        return linkType;
    }

    /** Ids (roots of subtrees for hierarchical types) the caller's groups grant. */
    public SortedSet<String> grantedIds() {
        return grantedIds;
    }

    /** True when the caller is granted nothing of this type: a query that must be limited to grants returns no elements. */
    public boolean grantsNothing() {
        return grantedIds.isEmpty();
    }

    /** Quotes an identifier for JQL or CQL: in double quotes, {@code \} and {@code "} escaped. */
    public static String quote(String id) {
        return "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String inList(String field, SortedSet<String> ids) {
        return field + " in (" + String.join(", ", ids.stream().map(QueryScope::quote).toList()) + ")";
    }

    /**
     * {@code project in ("ABC", "DEF")} for the projects the link narrows to; empty when it does not. A link that
     * narrows to no project at all cannot exist (the contracts allow 1..50 ids per field).
     */
    public Optional<String> jqlProjectClause() {
        requireType("Jira::Project", "Jira::Issue");
        return linkIds.map(ids -> inList("project", ids));
    }

    /** As {@link #jqlProjectClause()} with the granted projects too, for a service account that sees more than the user may. */
    public Optional<String> jqlProjectClause(boolean includeGrants) {
        requireType("Jira::Project", "Jira::Issue");
        if (!includeGrants) {
            return jqlProjectClause();
        }
        if (grantedIds.isEmpty()) {
            throw new IllegalStateException("the caller is granted no project: do not run the query (grantsNothing())");
        }
        String granted = inList("project", grantedIds);
        return Optional.of(linkIds.map(ids -> inList("project", ids) + " AND " + granted).orElse(granted));
    }

    /** {@code space in ("ENG")} for the spaces the link narrows to (pages are narrowed through their space). */
    public Optional<String> cqlSpaceClause() {
        requireType("Confluence::Space", "Confluence::Page");
        return linkIds.map(ids -> inList("space", ids));
    }

    /**
     * The index patterns an OpenSearch query is limited to, as a list to join with commas: for each prefix {@code p} the
     * pair {@code p} and {@code p-*}, never {@code p*} (that would reach {@code logs-team-ab} for {@code logs-team-a}).
     * With a link that narrows, only what both the grants and the link cover is left (the longer prefix of a pair when one
     * contains the other). Empty = nothing to query ({@link #grantsNothing()}, or the link and the grants do not meet).
     */
    public List<String> opensearchIndexPatterns() {
        requireType("OpenSearch::Index");
        SortedSet<String> prefixes = new TreeSet<>();
        if (linkIds.isEmpty()) {
            prefixes.addAll(grantedIds);
        } else {
            for (String granted : grantedIds) {
                for (String link : linkIds.get()) {
                    longerIfNested(granted, link).ifPresent(prefixes::add);
                }
            }
        }
        List<String> patterns = new ArrayList<>();
        for (String prefix : withoutCovered(prefixes)) {
            patterns.add(prefix);
            patterns.add(prefix + "-*");
        }
        return List.copyOf(patterns);
    }

    private static Optional<String> longerIfNested(String a, String b) {
        if (a.equals(b) || a.startsWith(b + "-")) {
            return Optional.of(a);
        }
        return b.startsWith(a + "-") ? Optional.of(b) : Optional.empty();
    }

    private static List<String> withoutCovered(SortedSet<String> prefixes) {
        List<String> kept = new ArrayList<>();
        for (String prefix : prefixes) {
            if (prefixes.stream().noneMatch(other -> !other.equals(prefix) && prefix.startsWith(other + "-"))) {
                kept.add(prefix);
            }
        }
        return kept;
    }

    /**
     * The query the server runs: {@code clause AND (userJql)} with a trailing {@code ORDER BY} kept at the end. The
     * clause is not optional: a call whose scope has nothing to add passes {@code userJql} through unchanged. A user query
     * that is blank becomes the clause alone; one with unbalanced parentheses or quotes is refused.
     *
     * @param clause for example {@link #jqlProjectClause()}; empty = no limit to add
     */
    public static String restrictJql(Optional<String> clause, String userJql) {
        String jql = userJql == null ? "" : userJql.strip();
        if (clause.isEmpty()) {
            return jql;
        }
        if (!balanced(jql)) {
            throw new IllegalArgumentException("the query has unbalanced parentheses or quotes");
        }
        int orderBy = topLevelOrderBy(jql);
        String filter = orderBy < 0 ? jql : jql.substring(0, orderBy).strip();
        String order = orderBy < 0 ? "" : " " + jql.substring(orderBy);
        return clause.get() + (filter.isEmpty() ? "" : " AND (" + filter + ")") + order;
    }

    /**
     * Parentheses open and close in order and quotes are closed: a user query like {@code a = 1) OR (project = HR} would
     * otherwise end the group the clause is wrapped around and widen it. {@code restrictJql} refuses such a query with an
     * {@link IllegalArgumentException}, which the platform reports as {@code invalid_argument}.
     */
    static boolean balanced(String jql) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < jql.length(); i++) {
            char c = jql.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth < 0) {
                return false;
            }
        }
        return depth == 0 && quote == 0;
    }

    /** Index of {@code ORDER BY} outside quotes and parentheses, or -1. */
    static int topLevelOrderBy(String jql) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < jql.length(); i++) {
            char c = jql.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && (i == 0 || Character.isWhitespace(jql.charAt(i - 1))
                    || jql.charAt(i - 1) == ')') && jql.regionMatches(true, i, "order", 0, 5)) {
                int j = i + 5;
                int spaces = 0;
                while (j < jql.length() && Character.isWhitespace(jql.charAt(j))) {
                    j++;
                    spaces++;
                }
                if (spaces > 0 && jql.regionMatches(true, j, "by", 0, 2)
                        && (j + 2 == jql.length() || Character.isWhitespace(jql.charAt(j + 2)))) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void requireType(String... allowed) {
        for (String type : allowed) {
            if (type.equals(resourceType)) {
                return;
            }
        }
        throw new IllegalStateException("this scope is for " + resourceType + ", not " + String.join(" or ", allowed));
    }
}
