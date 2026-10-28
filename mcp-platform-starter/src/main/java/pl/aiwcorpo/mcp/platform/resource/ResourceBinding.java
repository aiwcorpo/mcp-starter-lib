package pl.aiwcorpo.mcp.platform.resource;

/**
 * A {@code @ResourceRef} on a parameter of a tool: the argument that carries the identifiers, the Cedar type and the
 * resolver class that was named ({@code Void.class} for "the one of the type").
 */
public record ResourceBinding(String argument, String type, Class<?> resolver) {
}
