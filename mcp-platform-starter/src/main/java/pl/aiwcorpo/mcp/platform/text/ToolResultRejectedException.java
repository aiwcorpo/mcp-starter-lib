package pl.aiwcorpo.mcp.platform.text;

/**
 * A tool returned a result the platform will not pass on: it does not match the tool's {@code outputSchema} or it is
 * larger than {@code aiwcorpo.mcp.platform.tools.max-result-size}. Its own type, a plain {@link RuntimeException}, so
 * that a {@code ToolErrorMapping} of the server for {@code IllegalStateException} (a common choice for backend client
 * state errors) cannot turn a developer's mistake into {@code not_found_or_denied}: the mapper classifies this
 * exception first and always as {@code internal_error}. The message names a place and a rule or a size, never a value.
 */
public final class ToolResultRejectedException extends RuntimeException {

    public ToolResultRejectedException(String message) {
        super(message);
    }
}
