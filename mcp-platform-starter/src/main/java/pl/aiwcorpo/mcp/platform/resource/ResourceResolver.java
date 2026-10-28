package pl.aiwcorpo.mcp.platform.resource;

import java.util.Optional;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;

/**
 * Turns the identifier from a tool argument into the resource as the target system reports it, with what the policy
 * needs: the canonical id, the attributes and the parent (cedar/entity-builder.md, section 4). A resolver is a Spring
 * bean; it calls the system with the caller's own credential ({@code CredentialContext.require(system)}), so what the
 * system hides from the user is not found.
 *
 * <pre>{@code
 * @Component
 * class JiraIssueResolver implements ResourceResolver<JiraIssue> {
 *     public String resourceType() { return "Jira::Issue"; }
 *     public Optional<JiraIssue> fetch(String key) { return jira.issue(key); }          // empty for 404 and 403
 *     public ResolvedResource describe(JiraIssue issue) {
 *         return ResolvedResource.of("Jira::Issue", issue.key(), Map.of("key", issue.key()))
 *                 .withParent(ResolvedResource.of("Jira::Project", issue.projectKey(),
 *                         Map.of("key", issue.projectKey(), "category", issue.projectCategory())));
 *     }
 * }
 * }</pre>
 *
 * <p>Rules:
 * <ul>
 *   <li>The id and the parent of the result are what the system returns, never the argument: the argument may differ in
 *       case, and an issue that was moved to another project has the new project (cedar/entity-builder.md).</li>
 *   <li>{@link #fetch} returns empty when the system says the resource does not exist <em>or</em> that the user may not
 *       see it (404 and 403 alike); the platform answers {@code not_found_or_denied} for both. It throws for a failure of
 *       the system (timeout, 5xx), which the caller sees as {@code upstream_unavailable}, and never answers "denied" for
 *       it.</li>
 *   <li>A result is fetched at most once per request and type/id: the platform caches it for the duration of the call,
 *       and a tool can read it back with {@link ResourceContext#fetched}.</li>
 *   <li>Required classification attributes must be present: {@code Jira::Project.category} ({@code ""} when the project
 *       has none), {@code Confluence::Space.labels}, {@code OpenSearch::Index.tags}. A resource without them is denied.</li>
 * </ul>
 *
 * @param <T> what the system call returns, kept in the per-request cache for the tool to reuse
 */
public interface ResourceResolver<T> {

    /** The Cedar resource type this resolver resolves, one of the contracts' {@code ResourceType}. */
    String resourceType();

    /**
     * @param id the identifier from the argument, already checked against the pattern of {@link #resourceType()}
     * @return the object the system returned, empty when it does not exist or the user may not see it
     * @throws Exception when the system could not answer
     */
    Optional<T> fetch(String id) throws Exception;

    /**
     * Fetches many resources at once, for the elements of a result (one call to the system for a page of hits instead of one
     * per hit). The default calls {@link #fetch} for each id; override it with a bulk call (a JQL {@code key in (...)}, a
     * multi-get) when the system has one. Ids the system does not return, or the user may not see, are left out of the map.
     * A failure is treated as for {@link #fetch}, except that elements of a result that could not be fetched are removed
     * from it instead of ending the call.
     *
     * @param ids at most {@code tools.max-filtered-elements}, each already checked against the pattern of the type
     * @return fetched objects by the id that was asked for
     */
    default java.util.Map<String, T> fetchAll(java.util.Collection<String> ids) throws Exception {
        java.util.Map<String, T> fetched = new java.util.LinkedHashMap<>();
        for (String id : ids) {
            fetch(id).ifPresent(value -> fetched.put(id, value));
        }
        return fetched;
    }

    /** The resource the policy is evaluated on, built from what {@link #fetch} returned. */
    ResolvedResource describe(T fetched);
}
