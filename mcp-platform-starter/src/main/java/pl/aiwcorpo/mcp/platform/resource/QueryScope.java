package pl.aiwcorpo.mcp.platform.resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import pl.aiwcorpo.mcp.cedar.entities.QueryConstraint;
import pl.aiwcorpo.mcp.cedar.entities.ResourceTypes;

/**
 * What a search or list query must be limited to for one resource type (cedar/entity-builder.md, section 8, CC-036),
 * and the clauses to put in the query of the system. Obtained from {@link ResourcePolicy#queryScope}. It saves traffic
 * and does not reveal how many elements the caller may not see; it does not replace {@link ResultFilter}, which is the
 * control.
 *
 * <ul>
 *   <li>{@link #linkIds()}: the ids a search was narrowed to with {@link #narrowedTo} (the container ids named in an argument
 *       of the tool). Always put into the query ({@link #jqlProjectClause()}, {@link #cqlSpaceClause()}). Empty = no narrowing,
 *       which is what a scope straight from {@link ResourcePolicy#queryScope} is: since contracts 5.0.0 the call carries no
 *       virtual server and the token narrows nothing. The name is kept from 3.0.0-beta for source compatibility of the servers.</li>
 *   <li>{@link #grantedIds()}: what the groups of the caller grant, from the scope mapping of the policy data. A system that
 *       applies the user's own rights (a user token) needs no clause for it; where it does not (a service account, a system
 *       without per-user rights) the query is limited to it, and <b>an empty set means the query is not
 *       run</b> ({@link #grantsNothing()}), never "no filter".</li>
 *   <li>Identifiers match the patterns the server declares for its types and are still quoted ({@link #quote}): never glue them
 *       into a query without quotes.</li>
 * </ul>
 *
 * <p>The scope itself is data driven and has no knowledge of any system. The clause builders ({@code jql...}, {@code cql...},
 * {@code opensearch...}) are helpers for the query languages of the systems a server may speak to: a server uses the ones for
 * its own system and ignores the rest. They work on the ids of any scope type; which type holds the projects, spaces or index
 * prefixes is the server's declaration.
 */
public final class QueryScope {

    private final String resourceType;
    private final ResourceTypes types;
    private final Optional<SortedSet<String>> narrowing;
    private final String narrowingType;
    private final SortedSet<String> grantedIds;
    private final boolean narrowedByArgument;

    private QueryScope(QueryConstraint constraint, ResourceTypes types) {
        this.resourceType = constraint.resourceType();
        this.types = types;
        this.narrowing = Optional.empty();
        this.narrowingType = constraint.resourceType();
        this.grantedIds = constraint.granted();
        this.narrowedByArgument = false;
    }

    private QueryScope(QueryScope base, SortedSet<String> narrowedIds, String narrowingType) {
        this.resourceType = base.resourceType;
        this.types = base.types;
        this.narrowing = Optional.of(narrowedIds);
        this.narrowingType = narrowingType;
        this.grantedIds = base.grantedIds;
        this.narrowedByArgument = true;
    }

    /**
     * The scope narrowed to the container ids named in an argument of the tool (project keys, space keys), for a search the model limits to a
     * container (they are not fields of a condition, CC-160). The ids go into the <b>scope part</b> of the query ({@code project in (...)},
     * {@code space in (...)}), after the conditions in parentheses, never into them, and the narrowing can only shrink the scope: narrowing a
     * scope that is already narrowed gives the intersection of the two, otherwise the argument itself; an empty intersection is
     * {@link #nothingToQuery()} (an empty result, or {@link #requireQueryable()} for a fetch by id). The grants stay a clause of their own
     * ({@code jql(fields, true)}), and with them an argument outside the grants is refused as for an empty grant.
     *
     * <p>Pass only ids of an argument that is a {@link ResourceRef} of the container type: the policy has judged each of them before the tool
     * runs ({@code ResourcePolicy#authorizeCall}), and this narrows the query to them; it does not replace that check.
     *
     * @param ids the container ids of the argument, checked against the {@code idPattern} of the scope's own type
     * @throws IllegalArgumentException for an id that is not a valid identifier of that type
     */
    public QueryScope narrowedTo(java.util.Collection<String> ids) {
        return narrowedTo(resourceType, ids);
    }

    /**
     * As {@link #narrowedTo(java.util.Collection)} for a scope whose argument names containers of another declared type: the pages of a space are
     * limited by the space keys, so the scope of the pages is narrowed with the type of the key.
     *
     * @param containerType the declared type of the {@link ResourceRef} argument that holds the ids
     */
    public QueryScope narrowedTo(String containerType, java.util.Collection<String> ids) {
        Objects.requireNonNull(ids, "ids");
        SortedSet<String> argument = new TreeSet<>();
        for (String id : ids) {
            if (id == null || !types.isValidId(containerType, id)) {
                throw new IllegalArgumentException("not a valid " + containerType + " identifier");
            }
            argument.add(id);
        }
        if (narrowing.isPresent()) {
            argument.retainAll(narrowing.get());
        }
        return new QueryScope(this, argument, containerType);
    }

    /** True for a scope made by {@link #narrowedTo}; {@link #linkIds()} are then the argument (intersected with an earlier narrowing). */
    public boolean narrowedByArgument() {
        return narrowedByArgument;
    }

    /**
     * @param types the declared resource types of the server, whose {@code idPattern}s judge the ids passed to {@link #narrowedTo} and the
     *              index names of {@link #resolveIndexNames}
     */
    public static QueryScope of(QueryConstraint constraint, ResourceTypes types) {
        return new QueryScope(Objects.requireNonNull(constraint, "constraint"), Objects.requireNonNull(types, "types"));
    }

    /** The type asked for, for example {@code Jira::Project}. */
    public String resourceType() {
        return resourceType;
    }

    /** The ids the scope was narrowed to by {@link #narrowedTo}, or empty when it is not narrowed. */
    public Optional<SortedSet<String>> linkIds() {
        return narrowing;
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
        if (ids.isEmpty()) {
            throw new IllegalStateException("the scope excludes everything (nothingToQuery()): do not run the query");
        }
        return field + " in (" + String.join(", ", ids.stream().map(QueryScope::quote).toList()) + ")";
    }

    /**
     * {@code project in ("ABC", "DEF")} for the projects the scope was narrowed to by {@link #narrowedTo}; empty when it was not.
     */
    public Optional<String> jqlProjectClause() {
        return narrowing.map(ids -> inList("project", ids));
    }

    /** As {@link #jqlProjectClause()} with the granted projects too, for a service account that sees more than the user may. */
    public Optional<String> jqlProjectClause(boolean includeGrants) {
        if (!includeGrants) {
            return jqlProjectClause();
        }
        if (grantedIds.isEmpty()) {
            throw new IllegalStateException("the caller is granted no project: do not run the query (grantsNothing())");
        }
        requireOverlapWithGrants();
        String granted = inList("project", grantedIds);
        return Optional.of(narrowing.map(ids -> inList("project", ids) + " AND " + granted).orElse(granted));
    }

    /** {@code space in ("ENG")} for the spaces the scope was narrowed to (pages are narrowed through their space). */
    public Optional<String> cqlSpaceClause() {
        return narrowing.map(ids -> inList("space", ids));
    }

    /**
     * The index patterns that name the scope, as a list to join with commas, <b>for {@code GET _resolve/index/<expression>} only</b>
     * (CC-160): a search, {@code _count} or an aggregation goes to the concrete names of {@link #resolveIndexNames}, never to
     * these patterns, because {@code p-*} also matches an alias that points at the indices of another team. Each prefix {@code p}
     * gives the pair {@code p} and {@code p-*}, never {@code p*} (that would reach {@code logs-team-ab} for {@code logs-team-a}).
     *
     * @throws IllegalStateException when there is nothing to query ({@link #grantsNothing()}): an empty expression would make OpenSearch search every index, so the call must end with no result
     *         instead. Check {@link #nothingToQuery()} first.
     */
    public List<String> opensearchIndexPatterns() {
        List<String> patterns = indexPatterns();
        if (patterns.isEmpty()) {
            throw new IllegalStateException("the caller may query no index (grantsNothing()): "
                    + "do not run the query; an empty index expression would search every index");
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
     * <p>Otherwise it is {@link #grantsNothing()}: ask it where the query is limited by grants (a service account,
     * {@code includeGrants}, index prefixes); a system that applies the user's own rights needs no grant to run the query.
     */
    public boolean nothingToQuery() {
        if (narrowing.isPresent() && narrowing.get().isEmpty()) {
            return true;
        }
        return grantedIds.isEmpty();
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
     * sorted. <b>Aliases and data streams are never included, whatever their name:</b> an alias named
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
        Set<String> prefixes = new TreeSet<>(indexPrefixes());
        SortedSet<String> names = new TreeSet<>();
        for (tools.jackson.databind.JsonNode index : resolvedIndices(resolveIndexResponse)) {
            String name = index.path("name").isString() ? index.path("name").asString() : null;
            if (name != null && types.isValidId(resourceType, name)
                    && prefixes.stream().anyMatch(prefix -> name.equals(prefix) || name.startsWith(prefix + "-"))) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    /**
     * The target of a search, {@code _count} or aggregation: the names of {@link #resolveIndexNames} joined with commas, for the path of the
     * request. <b>Empty when there is nothing to query</b> (the scope excludes everything, or no index of the scope exists): the server must
     * then return an empty result without calling the system, because a request with an empty target searches every index. Use this and
     * not {@code String.join} on the names. A name is checked against the {@code idPattern} of the scope's type (CC-056, section 12 of the
     * entity builder): an index whose name does not match it (an administrator or an ingest pipeline can create names with {@code %} or
     * {@code =}) is dropped, so every name is safe in a path without encoding.
     */
    public Optional<String> opensearchSearchTarget(String resolveIndexResponse) {
        if (nothingToQuery()) {
            return Optional.empty();
        }
        List<String> names = resolveIndexNames(resolveIndexResponse);
        return names.isEmpty() ? Optional.empty() : Optional.of(String.join(",", names));
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
        if (argument == null || !types.isValidId(resourceType, argument)) {
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
     * Parameters of {@code GET _resolve/index/<expression>}: only open indices, and {@code ignore_unavailable=true} so that the concrete name
     * {@code p} of the pair {@code p,p-*} need not exist (the usual layout is {@code p-2026.10} only): without it OpenSearch answers 404
     * {@code index_not_found_exception} for the whole request, also when {@code p-2026.10} exists, and a search would return nothing without saying why.
     * The answer must not be cached between calls of a tool: the
     * window between the resolution and the query in which an index could be replaced by an alias (entity-builder.md, section 8) then lasts
     * as long as the cache does.
     */
    public static final java.util.Map<String, String> OPENSEARCH_RESOLVE_PARAMETERS = java.util.Map.of("expand_wildcards", "open",
            "ignore_unavailable", "true", "allow_no_indices", "true");

    /**
     * What to give {@link #resolveIndexNames} when {@code GET _resolve/index/} still answers 404 {@code index_not_found_exception} with the
     * parameters above (nothing of the scope exists at all): an empty answer, so nothing is queried. No other status is an empty answer. The
     * behaviour of the endpoint is taken from the source of OpenSearch and is to be confirmed on the devstack cluster (ws-01).
     */
    public static final String EMPTY_RESOLVE_RESPONSE = "{\"indices\":[],\"aliases\":[],\"data_streams\":[]}";

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

    /** The granted prefixes without those another prefix already covers. OpenSearch scopes are never narrowed by an argument. */
    private List<String> indexPrefixes() {
        return withoutCovered(new TreeSet<>(grantedIds));
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
     * user may. Only for the scope of the container type itself (the spaces): the grants of the pages of a space are subtree roots that
     * CQL cannot express as a space list.
     *
     * @throws IllegalStateException when the caller is granted no space, or the scope is for a type contained in the spaces
     */
    public Optional<String> cqlSpaceClause(boolean includeGrants) {
        if (!includeGrants) {
            return cqlSpaceClause();
        }
        requireContainerScope();
        if (grantedIds.isEmpty()) {
            throw new IllegalStateException("the caller is granted no space: do not run the query (grantsNothing())");
        }
        requireOverlapWithGrants();
        String granted = inList("space", grantedIds);
        return Optional.of(narrowing.map(ids -> inList("space", ids) + " AND " + granted).orElse(granted));
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
     * A JQL query built from structured conditions, limited to the projects the scope was narrowed to (and, with {@code includeGrants},
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

    /** A CQL query built from structured conditions, limited to the spaces the scope was narrowed to: see {@link StructuredQuery}. */
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
        List<String> clauses = new ArrayList<>();
        narrowing.ifPresent(ids -> clauses.add(inList("project", ids)));
        if (includeGrants) {
            if (grantedIds.isEmpty()) {
                throw new IllegalStateException("the caller is granted no project: do not run the query (grantsNothing())");
            }
            requireOverlapWithGrants();
            clauses.add(inList("project", grantedIds));
        }
        return clauses;
    }

    private List<String> spaceClauses(boolean includeGrants) {
        List<String> clauses = new ArrayList<>();
        narrowing.ifPresent(ids -> clauses.add(inList("space", ids)));
        if (includeGrants) {
            requireContainerScope();
            if (grantedIds.isEmpty()) {
                throw new IllegalStateException("the caller is granted no space: do not run the query (grantsNothing())");
            }
            requireOverlapWithGrants();
            clauses.add(inList("space", grantedIds));
        }
        return clauses;
    }

    /** A scope narrowed by an argument that the grants do not touch is empty for a query limited by grants. */
    private void requireOverlapWithGrants() {
        if (narrowedByArgument && narrowing.isPresent() && java.util.Collections.disjoint(narrowing.get(), grantedIds)) {
            throw new IllegalStateException("the argument names nothing the caller is granted: do not run the query (nothingToQuery())");
        }
    }

    /** Grants can be put in a query as a list of containers only when the scope is the container type itself, not a type inside it. */
    private void requireContainerScope() {
        if (!resourceType.equals(narrowingType)) {
            throw new IllegalStateException("the grants of " + resourceType + " are roots of subtrees: ask for the scope of the container "
                    + "type (" + narrowingType + ") to limit by grants");
        }
    }
}
