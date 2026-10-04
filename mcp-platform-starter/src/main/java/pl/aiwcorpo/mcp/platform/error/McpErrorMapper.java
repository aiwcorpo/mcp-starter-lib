package pl.aiwcorpo.mcp.platform.error;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.aiwcorpo.mcp.platform.security.MissingPatException;

/**
 * Shared mapping of exceptions to SAFE MCP tool errors.
 *
 * <p>Returns a sanitized message + stable code to the AI client while the full detail (with the
 * correlation id from MDC) is logged internally. Never exposes stack traces, SQL, internal
 * hostnames or upstream error bodies to the caller.
 *
 * <p>Applied automatically to every tool call by {@code AuditingToolCallback} — see
 * {@link SanitizedToolException} for why that matters.
 *
 * <h2>Which messages are echoed back</h2>
 * Generic by default. Three cases have their own message surfaced verbatim, because all three are
 * authored by us and carry no upstream data:
 * <ul>
 *   <li>Any {@link ToolErrorMapping} a server registered (checked first).</li>
 *   <li>{@link MissingPatException} — names the missing header so the client can retry correctly.</li>
 *   <li>{@link IllegalArgumentException} — thrown by tool-argument validation and by domain record
 *       invariants. Echoing it lets the model correct its own call instead of guessing.</li>
 * </ul>
 * <b>Invariant that makes this safe:</b> {@code IllegalArgumentException} must never be thrown with a
 * message built from a backend response. Outbound adapters must let client exceptions
 * (e.g. {@code RestClientException}) propagate unchanged; they map to the generic
 * {@code internal_error}.
 *
 * <h2>Why the cause chain is walked</h2>
 * Since 1.0.2. The three cases above are decided on the exception ACTUALLY THROWN, and by the time
 * this mapper runs, that is never the exception a tool threw: Spring AI wraps whatever escapes a
 * {@code @Tool} method in a {@code ToolExecutionException} before {@code AuditingToolCallback}
 * catches it. Every one of those branches was therefore dead code in practice, and a caller who
 * simply forgot a header was told {@code internal_error} — accurate about nothing and actionable in
 * no way. Servers papered over it by registering a {@link ToolErrorMapping} that walked the chain
 * itself; that workaround is now redundant (and harmless: server mappings still win).
 *
 * <p>The consequence to keep in mind: the invariant above now actually bites. Before, a message
 * built from a backend response and wrapped in an {@code IllegalArgumentException} was hidden by
 * accident, because the wrapper stopped the mapper from ever looking at it. It no longer is. An
 * {@code IllegalArgumentException} carrying upstream text — {@code URI.create()} on a
 * backend-supplied link is the classic way to produce one — will now reach the model.
 */
public class McpErrorMapper {

    private static final Logger LOG = LoggerFactory.getLogger(McpErrorMapper.class);

    /** Upper bound on an echoed message, so a pathological mapping cannot flood the model. */
    private static final int MAX_ECHOED_MESSAGE = 500;

    private final List<ToolErrorMapping> mappings;

    public McpErrorMapper() {
        this(List.of());
    }

    public McpErrorMapper(List<ToolErrorMapping> mappings) {
        this.mappings = List.copyOf(mappings);
    }

    /** Sanitized error surfaced to the MCP client. */
    public record SafeError(String code, String message) {
    }

    /** Cap on how far the cause chain is followed; also the guard against a cyclic one. */
    private static final int MAX_CAUSE_DEPTH = 10;

    public SafeError map(Throwable ex) {
        // Full detail stays internal; MDC correlationId ties it back to the request.
        LOG.error("Tool invocation failed: {}", ex.toString(), ex);

        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            Optional<SafeError> mapped = classify(current);
            if (mapped.isPresent()) {
                SafeError error = mapped.get();
                return new SafeError(error.code(), truncate(error.message()));
            }
            Throwable cause = current.getCause();
            // A self-referencing cause would loop for ever; the depth cap catches longer cycles.
            current = cause == current ? null : cause;
        }
        return new SafeError("internal_error", "The tool could not complete the request.");
    }

    /**
     * One level of the cause chain: the server's own mappings first, then the platform's defaults.
     *
     * <p>Order matters twice over. Server mappings are consulted before the built-ins at the SAME
     * level, so a domain exception that also happens to be an {@link IllegalArgumentException} still
     * gets the server's code rather than {@code invalid_argument}. And the whole level is decided
     * before moving outward-to-inward, so a specific exception wrapped in a generic one is answered
     * by the generic one only when nothing recognises the specific.
     */
    private Optional<SafeError> classify(Throwable ex) {
        for (ToolErrorMapping mapping : mappings) {
            Optional<SafeError> mapped = mapping.map(ex);
            if (mapped.isPresent()) {
                return mapped;
            }
        }
        if (ex instanceof SanitizedToolException sanitized) {
            // Already mapped by an inner decorator. Re-mapping it would wrap "forbidden: …" inside
            // "internal_error: …" and lose both the code and the reason.
            return Optional.of(new SafeError(sanitized.getCode(), sanitized.getSafeMessage()));
        }
        if (ex instanceof MissingPatException) {
            return Optional.of(new SafeError("missing_credential",
                    echo(ex, "A required credential header is missing.")));
        }
        if (ex instanceof IllegalArgumentException) {
            return Optional.of(new SafeError("invalid_argument", echo(ex, "The request was invalid.")));
        }
        if (ex instanceof SecurityException) {
            return Optional.of(new SafeError("forbidden", "Not authorized for this operation."));
        }
        return Optional.empty();
    }

    /** Echo an author-written message, falling back when absent. */
    private static String echo(Throwable ex, String fallback) {
        String message = ex.getMessage();
        return (message == null || message.isBlank()) ? fallback : truncate(message);
    }

    private static String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "The tool could not complete the request.";
        }
        return message.length() <= MAX_ECHOED_MESSAGE
                ? message
                : message.substring(0, MAX_ECHOED_MESSAGE) + "…";
    }
}
