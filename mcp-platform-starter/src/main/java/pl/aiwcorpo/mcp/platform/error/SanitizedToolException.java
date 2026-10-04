package pl.aiwcorpo.mcp.platform.error;

/**
 * A tool failure whose message is already safe to hand to an AI client.
 *
 * <p>Spring AI's MCP server catches {@link Exception} around every tool call and puts
 * {@code e.getMessage()} verbatim into the {@code CallToolResult} content (with {@code isError=true}).
 * That means whatever message escapes a tool is read by the model. This exception exists so that the
 * only thing that ever escapes is a message produced by {@link McpErrorMapper} — never an upstream
 * HTTP body, a stack trace, an internal hostname or a SQL fragment.
 *
 * <p>Deliberately carries <b>no cause</b>: a cause would let detail leak back out through any handler
 * that prints it. The full detail is logged internally by {@link McpErrorMapper}, tied to the request
 * via the correlation id in the MDC.
 */
public class SanitizedToolException extends RuntimeException {

    private final String code;
    private final String safeMessage;

    public SanitizedToolException(McpErrorMapper.SafeError error) {
        super(error.code() + ": " + error.message());
        this.code = error.code();
        this.safeMessage = error.message();
    }

    /** Stable, machine-readable error code (e.g. {@code invalid_argument}). */
    public String getCode() {
        return code;
    }

    /**
     * The already-sanitized message, without the {@code code: } prefix that {@link #getMessage()}
     * carries.
     *
     * <p>Kept separately so that {@link McpErrorMapper} can rebuild the original
     * {@link McpErrorMapper.SafeError} when it meets this exception again inside a cause chain —
     * which happens whenever one decorator wraps another. Reconstructing it by splitting the message
     * on a colon would corrupt any message that legitimately contains one.
     */
    public String getSafeMessage() {
        return safeMessage;
    }
}
