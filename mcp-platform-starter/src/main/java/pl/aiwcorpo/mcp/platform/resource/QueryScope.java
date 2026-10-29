package pl.aiwcorpo.mcp.platform.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Quotes an identifier for JQL or CQL like the reference rendering of the contracts (CC-160): in double quotes, {@code \} and
     * {@code "} escaped, a control character refused ({@link IllegalArgumentException}).
     */
    public static String quote(String id) {
        return StructuredQuery.quote(id);
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
     * The index patterns that name the scope, as a list to join with commas, <b>for {@code GET _resolve/index/<expression>} only</b>
     * (CC-160): a search, {@code _count} or an aggregation goes to the concrete names of {@link #resolveIndexNames}, never to
     * these patterns, because {@code p-*} also matches an alias that points at the indices of another team. Each prefix {@code p}
     * gives the pair: for each prefix {@code p} the
     * pair {@code p} and {@code p-*}, never {@code p*} (that would reach {@code logs-team-ab} for {@code logs-team-a}).
     * With a link that narrows, only what both the grants and the link cover is left (the longer prefix of a pair when one
     * contains the other).
     *
     * @throws IllegalStateException when there is nothing to query ({@link #grantsNothing()}, or the link and the grants do
     *         not meet): an empty expression would make OpenSearch search every index, so the call must end with no result
     *         instead. Check {@link #nothingToQuery()} first.
     */
    public List<String> opensearchIndexPatterns() {
        List<String> patterns = indexPatterns();
        if (patterns.isEmpty()) {
            throw new IllegalStateException("the caller may query no index (grantsNothing or the link and the grants do not "
                    + "meet): do not run the query; an empty index expression would search every index");
        }
        return patterns;
    }

    /** The patterns joined with commas, for the path of a search request; throws like {@link #opensearchIndexPatterns()}. */
    public String opensearchIndexExpression() {
        return String.join(",", opensearchIndexPatterns());
    }

    /**
     * True when the scope excludes everything (CC-147): a search or a list must return an empty result without calling the system
     * (that is not an error), and the fetch of one resource by id must end with {@code not_found_or_denied}
     * ({@link #requireQueryable()}), the same answer as for a resource the user cannot see.
     *
     * <p>For OpenSearch, no index is both granted and inside the link. For the other grantable types it is {@link #grantsNothing()}:
     * ask it where the query is limited by grants (a service account, {@code includeGrants}); a system that applies the user's own
     * rights needs no grant to run the query.
     */
    public boolean nothingToQuery() {
        return "OpenSearch::Index".equals(resourceType) ? indexPatterns().isEmpty() : grantedIds.isEmpty();
    }

    /**
     * For the tools that fetch one resource by identifier: {@code not_found_or_denied} when {@link #nothingToQuery()}, without a call
     * to the system (CC-147).
     *
     * @throws pl.aiwcorpo.mcp.platform.error.SanitizedToolException {@code not_found_or_denied}
     */
    public void requireQueryable() {
        if (nothingToQuery()) {
            throw new pl.aiwcorpo.mcp.platform.error.SanitizedToolException(new pl.aiwcorpo.mcp.platform.error.McpErrorMapper.SafeError(
                    pl.aiwcorpo.mcp.platform.error.ToolErrorCode.NOT_FOUND_OR_DENIED.code(),
                    pl.aiwcorpo.mcp.platform.error.ToolErrorCode.NOT_FOUND_OR_DENIED_MESSAGE));
        }
    }

    /**
     * The concrete index names an OpenSearch query may run on (CC-160): the entries of {@code indices} in the answer of
     * {@code GET _resolve/index/}{@link #opensearchIndexExpression()} whose name is a granted prefix or starts with {@code <prefix>-}
     * (and inside the link), sorted. <b>Aliases and data streams are never included, whatever their name:</b> an alias named
     * {@code logs-team-a-all} can point at the indices of another team, and counts and aggregations over it would include them. The
     * query then goes to this list of names and never to a pattern. An empty list means there is nothing to query: return an empty
     * result without calling the system.
     *
     * <p>Between this resolution and the query there is a short window in which an administrator could replace an index with an
     * alias of the same name; the contract accepts that risk (entity-builder.md, section 8).
     *
     * @param resolveIndexResponse the JSON answer of OpenSearch: {@code {"indices":[{"name":...}],"aliases":[...],"data_streams":[...]}}
     * @throws IllegalArgumentException when it is not that JSON
     */
    public List<String> resolveIndexNames(String resolveIndexResponse) {
        requireType("OpenSearch::Index");
        Set<String> prefixes = new TreeSet<>(indexPrefixes());
        SortedSet<String> names = new TreeSet<>();
        for (tools.jackson.databind.JsonNode index : resolvedIndices(resolveIndexResponse)) {
            String name = index.path("name").isString() ? index.path("name").asString() : null;
            if (name != null && prefixes.stream().anyMatch(prefix -> name.equals(prefix) || name.startsWith(prefix + "-"))) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * An index named by the model as an argument (a resource id): one concrete name. A value that is a pattern, a list, {@code _all} or
     * has a leading dot is {@code invalid_argument} before any call to the system. After the resolution ({@link #resolveIndexNames}) an
     * alias, a data stream, an index outside the grant and a name the system does not know are all {@code not_found_or_denied}.
     *
     * @param resolveIndexResponse the answer of {@code GET _resolve/index/}{@link #opensearchIndexExpression()}
     * @return the name to query
     * @throws pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException for a value that is not an index name
     * @throws pl.aiwcorpo.mcp.platform.error.SanitizedToolException {@code not_found_or_denied}
     */
    public String resolveIndexArgument(String argument, String resolveIndexResponse) {
        requireType("OpenSearch::Index");
        if (argument == null || !pl.aiwcorpo.mcp.cedar.entities.ResourceIds.isValid("OpenSearch::Index",
                pl.aiwcorpo.mcp.cedar.entities.ResourceIds.Kind.RESOURCE, argument)) {
            throw new pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException("The index name is not valid.");
        }
        if (!resolveIndexNames(resolveIndexResponse).contains(argument)) {
            requireQueryable();
            throw new pl.aiwcorpo.mcp.platform.error.SanitizedToolException(new pl.aiwcorpo.mcp.platform.error.McpErrorMapper.SafeError(
                    pl.aiwcorpo.mcp.platform.error.ToolErrorCode.NOT_FOUND_OR_DENIED.code(),
                    pl.aiwcorpo.mcp.platform.error.ToolErrorCode.NOT_FOUND_OR_DENIED_MESSAGE));
        }
        return argument;
    }

    private static tools.jackson.databind.JsonNode resolvedIndices(String resolveIndexResponse) {
        tools.jackson.databind.JsonNode answer;
        try {
            answer = tools.jackson.databind.json.JsonMapper.builder().build().readTree(resolveIndexResponse);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("the _resolve/index answer is not JSON");
        }
        if (!answer.isObject() || !answer.path("indices").isArray()) {
            throw new IllegalArgumentException("the _resolve/index answer has no indices list");
        }
        return answer.path("indices");
    }

    /**
     * Search parameters for the request to the list of names of {@link #resolveIndexNames}: only open indexes, no error for a name
     * that disappeared since it was resolved.
     */
    public static final java.util.Map<String, String> OPENSEARCH_SEARCH_PARAMETERS = java.util.Map.of(
            "expand_wildcards", "open", "ignore_unavailable", "true", "allow_no_indices", "true");

    private List<String> indexPatterns() {
        List<String> patterns = new ArrayList<>();
        for (String prefix : indexPrefixes()) {
            patterns.add(prefix);
            patterns.add(prefix + "-*");
        }
        return List.copyOf(patterns);
    }

    /** The granted prefixes inside the link, without those another prefix already covers. */
    private List<String> indexPrefixes() {
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
        return withoutCovered(prefixes);
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
     * As {@link #cqlSpaceClause()} with the spaces the caller's groups grant, for a service account that sees more than the
     * user may. Only for {@code Confluence::Space}: the grants of pages are subtree roots that CQL cannot express as a space list.
     *
     * @throws IllegalStateException when the caller is granted no space, or the scope is for pages
     */
    public Optional<String> cqlSpaceClause(boolean includeGrants) {
        requireType("Confluence::Space", "Confluence::Page");
        if (!includeGrants) {
            return cqlSpaceClause();
        }
        if (!"Confluence::Space".equals(resourceType)) {
            throw new IllegalStateException("the grants of pages are page subtrees: ask for the Confluence::Space scope to limit by grants");
        }
        if (grantedIds.isEmpty()) {
            throw new IllegalStateException("the caller is granted no space: do not run the query (grantsNothing())");
        }
        String granted = inList("space", grantedIds);
        return Optional.of(linkIds.map(ids -> inList("space", ids) + " AND " + granted).orElse(granted));
    }

    /**
     * @deprecated since 3.0.0-beta-6: query the concrete names of {@link #resolveIndexNames} instead (CC-160); counts and aggregations
     *             over them are limited to the scope, so there is nothing to refuse
     *
     * <p>Refuses a query that aggregates over an <em>alias</em> or a data stream: a pattern {@code p-*} also matches an alias named
     * {@code p-...} that may point at indexes outside the scope, and filtering the hits cannot correct counts and aggregates.
     * Call it with the body of {@code GET _resolve/index/<expression>} ({@link #opensearchIndexExpression()}) before an
     * aggregating search, {@code _count} or a search that reports {@code hits.total}.
     *
     * @param resolveIndexResponse the JSON answer of OpenSearch: {@code {"indices":[...],"aliases":[...],"data_streams":[...]}}
     * @throws pl.aiwcorpo.mcp.platform.error.SanitizedToolException {@code access_denied} when the answer lists an alias or a
     *         data stream; {@link IllegalArgumentException} when it is not that JSON
     */
    @Deprecated
    public static void requireNoAliases(String resolveIndexResponse) {
        resolvedIndices(resolveIndexResponse);
        tools.jackson.databind.JsonNode answer = tools.jackson.databind.json.JsonMapper.builder().build().readTree(resolveIndexResponse);
        if (answer.path("aliases").size() > 0 || answer.path("data_streams").size() > 0) {
            throw new pl.aiwcorpo.mcp.platform.error.SanitizedToolException(new pl.aiwcorpo.mcp.platform.error.McpErrorMapper.SafeError(
                    "access_denied", "Counts and aggregations are not available over aliases or data streams."));
        }
    }

    /**
     * A JQL query built from structured conditions, limited to the projects of the link (and, with {@code includeGrants},
     * to the granted ones): see {@link StructuredQuery}. The query is never taken from the model as text.
     *
     * @param fields the fields the tool lets the model put conditions on; a subset of {@link StructuredQuery#JQL_CONDITION_FIELDS}
     *               (CC-160), anything else is an {@link IllegalArgumentException}
     */
    public StructuredQuery.Builder jql(Set<String> fields) {
        return jql(fields, StructuredQuery.JQL_ORDER_FIELDS);
    }

    /** As {@link #jql(Set)} with the fields {@code ORDER BY} may use, a subset of {@link StructuredQuery#JQL_ORDER_FIELDS}. */
    public StructuredQuery.Builder jql(Set<String> fields, Set<String> orderFields) {
        return new StructuredQuery.Builder(StructuredQuery.Language.JQL, fields, orderFields,
                jqlProjectClause().stream().toList());
    }

    /** As {@link #jql(Set)}, with the granted projects too (see {@link #jqlProjectClause(boolean)}). */
    public StructuredQuery.Builder jql(Set<String> fields, boolean includeGrants) {
        return new StructuredQuery.Builder(StructuredQuery.Language.JQL, fields, StructuredQuery.JQL_ORDER_FIELDS,
                projectClauses(includeGrants));
    }

    /** A CQL query built from structured conditions, limited to the spaces of the link: see {@link StructuredQuery}. */
    public StructuredQuery.Builder cql(Set<String> fields) {
        return cql(fields, StructuredQuery.CQL_ORDER_FIELDS);
    }

    /** As {@link #cql(Set)} with the fields {@code ORDER BY} may use, a subset of {@link StructuredQuery#CQL_ORDER_FIELDS}. */
    public StructuredQuery.Builder cql(Set<String> fields, Set<String> orderFields) {
        return new StructuredQuery.Builder(StructuredQuery.Language.CQL, fields, orderFields,
                cqlSpaceClause().stream().toList());
    }

    /** As {@link #cql(Set)}, with the granted spaces too (see {@link #cqlSpaceClause(boolean)}). */
    public StructuredQuery.Builder cql(Set<String> fields, boolean includeGrants) {
        return new StructuredQuery.Builder(StructuredQuery.Language.CQL, fields, StructuredQuery.CQL_ORDER_FIELDS,
                spaceClauses(includeGrants));
    }

    /** The clauses of {@link #jqlProjectClause(boolean)}, one per condition, so that the query reads {@code project in (...) AND project in (...)}. */
    private List<String> projectClauses(boolean includeGrants) {
        requireType("Jira::Project", "Jira::Issue");
        List<String> clauses = new ArrayList<>();
        linkIds.ifPresent(ids -> clauses.add(inList("project", ids)));
        if (includeGrants) {
            if (grantedIds.isEmpty()) {
                throw new IllegalStateException("the caller is granted no project: do not run the query (grantsNothing())");
            }
            clauses.add(inList("project", grantedIds));
        }
        return clauses;
    }

    private List<String> spaceClauses(boolean includeGrants) {
        requireType("Confluence::Space", "Confluence::Page");
        List<String> clauses = new ArrayList<>();
        linkIds.ifPresent(ids -> clauses.add(inList("space", ids)));
        if (includeGrants) {
            if (!"Confluence::Space".equals(resourceType)) {
                throw new IllegalStateException("the grants of pages are page subtrees: ask for the Confluence::Space scope to limit by grants");
            }
            if (grantedIds.isEmpty()) {
                throw new IllegalStateException("the caller is granted no space: do not run the query (grantsNothing())");
            }
            clauses.add(inList("space", grantedIds));
        }
        return clauses;
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
