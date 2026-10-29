package pl.aiwcorpo.mcp.platform.resource;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.cedar.entities.ResourceIds;
import pl.aiwcorpo.mcp.cedar.entities.ResourceScopes;

/**
 * The resource a caller's grant is judged on before the system is asked anything (CC-146): the resource named in an argument, with
 * the container the identifier names (the project of an issue key, the group of a GitLab path) and the classification attributes
 * the contract requires filled with their least restrictive values; nothing is taken from the system.
 * A policy that denies this resource grants the caller nothing of that type whatever the real resource looks like, so the answer
 * is {@code not_found_or_denied} without a resolver call, without building the entity from system data and without the
 * {@code internal_error} that a missing classification would give: that error would tell a caller with no grant that the
 * resource exists.
 *
 * <p>Types whose container cannot be derived from the identifier ({@code Confluence::Page}, {@code ServiceDesk::Ticket}) have no
 * probe: they are judged after resolution as before.
 */
final class GrantProbe {

    private GrantProbe() {
    }

    /** The probe for the argument value {@code id} of a {@code type} reference, empty when the type has none or the id has no usable container. */
    static Optional<ResolvedResource> of(String type, String id) {
        if (id == null) {
            return Optional.empty();
        }
        return switch (type) {
            case "Jira::Project" -> jiraProject(id);
            case "Jira::Issue" -> {
                int dash = id.lastIndexOf('-');
                yield dash > 0 && valid(type, id).isPresent() ? jiraProject(id.substring(0, dash))
                        .map(project -> new ResolvedResource(type, id, Map.of("key", id), Optional.of(project))) : Optional.empty();
            }
            case "Confluence::Space" -> valid(type, id).map(space -> ResolvedResource.of(type, id, Map.of("key", id, "labels", List.of())));
            case "GitLab::Group" -> gitlabGroup(id);
            case "GitLab::Project" -> {
                int slash = id.lastIndexOf('/');
                yield slash > 0 && valid(type, id).isPresent() ? gitlabGroup(id.substring(0, slash)).map(group -> {
                    Map<String, Object> ancestors = new LinkedHashMap<>();
                    ResourceScopes.pathPrefixes(group.id()).forEach(prefix -> ancestors.put(prefix, List.of()));
                    return new ResolvedResource(type, id, Map.of("path", id, "ancestorClassifications", ancestors),
                            Optional.of(group));
                }) : Optional.empty();
            }
            case "OpenSearch::Index" -> valid(type, id).map(index -> ResolvedResource.of(type, id, Map.of("name", id, "tags", List.of())));
            case "ServiceDesk::Queue" -> valid(type, id).map(queue -> ResolvedResource.of(type, id, Map.of("id", id)));
            default -> Optional.empty();
        };
    }

    /**
     * The probe for a caller who holds no scope on the container type, for the types whose container the identifier does not name: the
     * page or the ticket in a container that no scope of the caller covers (a sentinel id, which cannot be granted because the caller has
     * no scope of that type at all; when the call comes through a virtual server link that narrows the container type, the first id of the link, so that the
     * narrowing, which only removes what lies outside it, does not deny the probe). A policy that denies it grants nothing without scopes (the service account of
     * {@code credential-type.cedar}); one that allows it (the reading of {@code read.cedar}, which follows the user's own rights in the
     * system) leaves the decision to the full check after resolution.
     */
    static Optional<ResolvedResource> withoutScopes(String type, String id, String containerId) {
        return switch (type) {
            case "ServiceDesk::Ticket" -> valid(type, id).map(ticket -> new ResolvedResource(type, id, Map.of("id", id),
                    Optional.of(ResolvedResource.of("ServiceDesk::Queue", containerId, Map.of("id", containerId)))));
            case "Confluence::Page" -> valid(type, id).map(page -> new ResolvedResource(type, id,
                    Map.of("id", id, "ancestorIds", List.of()), Optional.of(ResolvedResource.of("Confluence::Space", containerId,
                            Map.of("key", containerId, "labels", List.of())))));
            default -> Optional.empty();
        };
    }

    private static Optional<ResolvedResource> jiraProject(String key) {
        return valid("Jira::Project", key)
                .map(project -> ResolvedResource.of("Jira::Project", key, Map.of("key", key, "category", "")));
    }

    private static Optional<ResolvedResource> gitlabGroup(String path) {
        return valid("GitLab::Group", path).map(group -> {
            Map<String, Object> ancestors = new LinkedHashMap<>();
            ResourceScopes.pathPrefixes(path).stream().filter(prefix -> !prefix.equals(path))
                    .forEach(prefix -> ancestors.put(prefix, List.of()));
            return ResolvedResource.of("GitLab::Group", path, Map.of("path", path, "ancestorClassifications", ancestors));
        });
    }

    private static Optional<String> valid(String type, String id) {
        return ResourceIds.isValid(type, ResourceIds.Kind.RESOURCE, id) ? Optional.of(id) : Optional.empty();
    }

    /** The string values of a reference argument: a string, or the strings of a list; anything else is left to the later checks. */
    static List<String> values(Object argument) {
        if (argument instanceof CharSequence text) {
            return List.of(text.toString());
        }
        if (argument instanceof Collection<?> items) {
            return items.stream().filter(CharSequence.class::isInstance).map(Object::toString).limit(100).toList();
        }
        return List.of();
    }
}
