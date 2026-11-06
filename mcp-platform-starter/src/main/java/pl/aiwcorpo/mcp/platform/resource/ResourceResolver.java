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
 *         ResolvedResource project = ResolvedResource.of("Jira::Project", issue.projectKey(),
 *                 Map.of("key", issue.projectKey(), "category", issue.projectCategory()))
 *                 .withScopes(List.of(Scopes.id("Jira::Project", issue.projectKey())));
 *         return ResolvedResource.of("Jira::Issue", issue.key(), Map.of("key", issue.key())).withParent(project)
 *                 .withScopes(project.scopes());
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
 *   <li>Required classification attributes must be present: the one the {@code classification} of the type's declaration in
 *       {@code resource-types.json} names, when it is {@code required} ({@code ""} or an empty set when the system has none to
 *       give). A resource without it is denied.</li>
 *   <li>The resource carries {@code scopes}: the scope ids of the resource and of every container it lies in
 *       ({@link pl.aiwcorpo.mcp.cedar.entities.ResolvedResource#withScopes}); the policies of the server decide with
 *       {@code principal in resource.scopes}. {@link pl.aiwcorpo.mcp.cedar.entities.Scopes} has the two derivations several systems
 *       need ({@code pathPrefixes}, {@code dashPrefixes}).</li>
 * </ul>
 *
 * <p>Three optional hooks let a server judge a grant before the system is asked anything; a resolver that does not override
 * them is judged only after the resolution (the default for a type whose policies need the resolved resource anyway).
 *
 * @param <T> what the system call returns, kept in the per-request cache for the tool to reuse
 */
public interface ResourceResolver<T> {

    /** The Cedar resource type this resolver resolves: a type the server declares in {@code mcp/policies/resource-types.json}. */
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

    /**
     * Opt-in, for a type whose identifier names its container (the project of an issue key, the group of a GitLab path): the resource as
     * the identifier alone describes it, with the container it names, {@code scopes} and the classification attributes at their least
     * restrictive values; nothing is taken from the system. The platform evaluates the policy on it before any call to the system
     * (CC-146): a policy that denies this resource grants the caller nothing of that type whatever the real resource looks like, so
     * the answer is {@code not_found_or_denied} without a resolver call and without the {@code internal_error} that a missing
     * classification would give, which would tell a caller with no grant that the resource exists.
     *
     * @param id an identifier that matches the pattern of the type
     * @return empty (the default) when the type has no probe or the identifier names no usable container
     */
    default Optional<ResolvedResource> probe(String id) {
        return Optional.empty();
    }

    /**
     * Opt-in, for a type whose identifier does not name its container (a page, a ticket): the scope types that grant it (for a page
     * {@code Confluence::Space} and {@code Confluence::Page}). When the caller holds none of them, the platform asks
     * {@link #probeOutsideScopes} before the resolver is called, and judges a resolved resource that lies in no granted scope as
     * missing ({@code not_found_or_denied}) when the evaluation of it fails. Empty (the default): no such check.
     */
    default java.util.List<String> scopeTypes() {
        return java.util.List.of();
    }

    /**
     * Opt-in with {@link #scopeTypes()}: the resource in a container that no scope of the caller covers (a sentinel container id that
     * cannot be granted). A policy that denies it grants nothing without scopes; one that allows it (it follows the user's own rights in
     * the system) leaves the decision to the full check after resolution.
     */
    default Optional<ResolvedResource> probeOutsideScopes(String id) {
        return Optional.empty();
    }

    /**
     * Opt-in: with the service account (the default credential type of the server) the system sees everything, so a failure on an existing
     * resource must not differ for the client from a missing one. When true, a resolver failure other than a missing credential is
     * answered {@code not_found_or_denied} (the operator still sees the cause in the log). Default false: the failure is reported as
     * {@code upstream_unavailable}.
     */
    default boolean hidesFailureForServiceAccount() {
        return false;
    }
}
