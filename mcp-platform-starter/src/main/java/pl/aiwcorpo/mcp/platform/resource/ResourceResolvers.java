package pl.aiwcorpo.mcp.platform.resource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;

/**
 * The resolver beans of the server, found by resource type or by class. Resolution goes through the per-call cache of
 * {@link ResourceContext}.
 */
public final class ResourceResolvers {

    private final Map<String, List<ResourceResolver<?>>> byType = new LinkedHashMap<>();

    public ResourceResolvers(List<ResourceResolver<?>> resolvers) {
        for (ResourceResolver<?> resolver : resolvers) {
            byType.computeIfAbsent(resolver.resourceType(), type -> new ArrayList<>()).add(resolver);
        }
    }

    /**
     * The resolver a {@link ResourceRef} names: the one of the class given, or the only one of the type.
     *
     * @throws IllegalStateException when there is none, the class is not a resolver bean of that type, or the choice is ambiguous
     */
    public ResourceResolver<?> forRef(String type, Class<?> resolverClass, String where) {
        List<ResourceResolver<?>> candidates = byType.getOrDefault(type, List.of());
        if (resolverClass == null || resolverClass == Void.class) {
            if (candidates.size() == 1) {
                return candidates.getFirst();
            }
            throw new IllegalStateException(where + ": " + (candidates.isEmpty()
                    ? "no ResourceResolver bean for " + type
                    : candidates.size() + " ResourceResolver beans for " + type + ", name one with @ResourceRef(resolver = ...)")
                    + " (a resolver is a Spring bean whose resourceType() is " + type + ")");
        }
        return candidates.stream().filter(resolverClass::isInstance).findFirst().orElseThrow(() -> new IllegalStateException(
                where + ": " + resolverClass.getName() + " is not a ResourceResolver bean with resourceType() " + type));
    }

    /** The only resolver of a type, for result filtering. */
    public Optional<ResourceResolver<?>> only(String type) {
        List<ResourceResolver<?>> candidates = byType.getOrDefault(type, List.of());
        return candidates.size() == 1 ? Optional.of(candidates.getFirst()) : Optional.empty();
    }

    /**
     * Resolves {@code id} within the current call: from the cache, or by the system call of {@code resolver}.
     *
     * @return empty when the system says the resource is not there or not visible to the caller
     * @throws ResolverFailedException when the system could not answer
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    Optional<ResolvedResource> resolve(ResourceResolver<?> resolver, String type, String id) {
        ResourceContext context = ResourceContext.require();
        Optional<ResourceContext.Resolved> cached = context.cached(type, id);
        if (cached.isPresent()) {
            return Optional.ofNullable(cached.get().resource());
        }
        try {
            Optional<?> fetched = resolver.fetch(id);
            if (fetched.isEmpty()) {
                context.remember(type, id, new ResourceContext.Resolved(null, null));
                return Optional.empty();
            }
            ResolvedResource resource = ((ResourceResolver) resolver).describe(fetched.get());
            context.remember(type, id, new ResourceContext.Resolved(fetched.get(), resource));
            return Optional.ofNullable(resource);
        } catch (Exception ex) {
            throw new ResolverFailedException(ex);
        }
    }

    /**
     * Resolves the ids of the elements of a result with one {@link ResourceResolver#fetchAll} call and puts them in the cache
     * of the call; what could not be fetched stays uncached, so each such element is tried alone and removed if it fails too.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    void prefetch(ResourceResolver<?> resolver, String type, java.util.Collection<String> ids) {
        ResourceContext context = ResourceContext.require();
        java.util.List<String> missing = ids.stream().distinct().filter(id -> id != null && context.cached(type, id).isEmpty()).toList();
        if (missing.size() < 2) {
            return;
        }
        try {
            java.util.Map<String, ?> fetched = resolver.fetchAll(missing);
            for (String id : missing) {
                Object value = fetched.get(id);
                if (value == null) {
                    context.remember(type, id, new ResourceContext.Resolved(null, null));
                } else {
                    context.remember(type, id, new ResourceContext.Resolved(value, ((ResourceResolver) resolver).describe(value)));
                }
            }
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(ResourceResolvers.class).warn("event=resolver_bulk_failed type={} detail=\"{}\"",
                    type, ex.getClass().getName());
        }
    }

    /** The system could not answer; the cause is what the mapper of C10 classifies (credential missing, backend unavailable). */
    static final class ResolverFailedException extends RuntimeException {
        ResolverFailedException(Throwable cause) {
            super("resolver failed", cause, false, false);
        }
    }
}
