package pl.aiwcorpo.mcp.platform.resource;

import java.util.List;
import java.util.function.Function;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;

/**
 * Removes from a search or list result the elements the caller may not see (CC-036, cedar/entity-builder.md, section
 * 8). Every element is judged on its own by the policy of the bundle, on the resource as the system reports it; an
 * element that cannot be resolved, built or evaluated is removed, never returned. A removed element is not an error of
 * the call.
 *
 * <p>A tool that returns records marked with {@link ResourceRef} on a component gets this done by the platform after it
 * returns; this interface is for the result that is not shaped that way (a tool that has the hits from a query in hand
 * and wants to drop them before it builds the result). The bean is {@link ResourcePolicy}; the methods work only while
 * a tool runs.
 */
public interface ResultFilter {

    /**
     * @param items      the elements the system returned, in its order
     * @param toResource the resource an element is, with its parent (a linked issue of another project has that project)
     * @return the elements the caller may see, in the original order
     */
    <T> List<T> retainAllowed(List<T> items, Function<? super T, ResolvedResource> toResource);

    /**
     * As {@link #retainAllowed(List, Function)} for elements that carry an identifier: each one is resolved by the
     * resolver of {@code resourceType} (cached for the call), so an element already resolved for an argument costs
     * nothing.
     */
    <T> List<T> retainAllowedById(String resourceType, List<T> items, Function<? super T, String> idOf);
}
