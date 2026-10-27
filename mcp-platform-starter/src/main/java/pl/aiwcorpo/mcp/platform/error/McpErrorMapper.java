package pl.aiwcorpo.mcp.platform.error;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;
import pl.aiwcorpo.mcp.platform.security.SecretRedactor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Maps whatever a tool threw to a C10 error the AI client may see, and logs the detail for operators.
 *
 * <h2>What the client sees</h2>
 * Generic by default ({@code internal_error}). Walking the cause chain (Spring AI wraps every exception of a
 * {@code @Tool} method in its own), at each level: the server's {@link ToolErrorMapping}s first, then
 * <ul>
 *   <li>{@link SanitizedToolException}: kept as it is;</li>
 *   <li>{@link CredentialRequiredException}: {@code credential_required} with {@code system} and the portal's
 *       {@code connectUrl};</li>
 *   <li>{@link InvalidToolArgumentException}: {@code invalid_argument} with its own message, so the model can
 *       correct the call. <b>Invariant:</b> never thrown with text from a backend response;</li>
 *   <li>any other {@link IllegalArgumentException} (libraries, conversions): {@code invalid_argument}, fixed
 *       text;</li>
 *   <li>{@link SecurityException}: {@code access_denied}, fixed text;</li>
 *   <li>backend unreachable ({@link ResourceAccessException}) or answering 502/503/504:
 *       {@code upstream_unavailable}, fixed text.</li>
 * </ul>
 * A code outside C10 becomes {@code internal_error}; fields C10 does not define for the code are dropped;
 * the message is cut to 500 characters; a result that still does not match {@code tool-error.schema.json}
 * (e.g. a required field missing) becomes {@code internal_error}. The current request's credential is removed from every message.
 *
 * <h2>What the log gets</h2>
 * One line per failure with the exception classes and messages of the chain (credential removed) and the
 * top frames of the innermost exception. Bodies of backend error responses are not logged: they are the
 * place where backends echo request headers.
 */
public class McpErrorMapper {

    private static final Logger LOG = LoggerFactory.getLogger(McpErrorMapper.class);

    /** C10 {@code Message}: 1..500 characters. */
    static final int MAX_MESSAGE = 500;

    private static final int MAX_CAUSE_DEPTH = 10;
    private static final int LOGGED_FRAMES = 8;
    private static final String GENERIC = "The tool could not complete the request.";
    private static final String INVALID_ARGUMENT_TEXT = "The arguments of the call are not valid.";
    private static final Schema TOOL_ERROR_SCHEMA = SchemaRegistry
            .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(SchemaLocation.of("classpath:mcp-contracts/schemas/tool-error.schema.json"));
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Code-specific fields allowed by {@code tool-error.schema.json}. */
    private static final Map<String, Set<String>> DETAILS_BY_CODE = Map.of(
            "access_denied", Set.of("requiredEntitlements"),
            "confirmation_required", Set.of("confirmationId", "confirmUrl"),
            "credential_required", Set.of("system", "connectUrl"),
            "rate_limited", Set.of("retryAfterSeconds"),
            "step_up_required", Set.of("stepUpUrl"));

    /** A C10 error: code, safe text and the code-specific fields. */
    public record SafeError(String code, String message, Map<String, Object> details) {

        public SafeError {
            details = details == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(details));
        }

        public SafeError(String code, String message) {
            this(code, message, Map.of());
        }
    }

    private final List<ToolErrorMapping> mappings;
    private final String connectUrlTemplate;

    public McpErrorMapper() {
        this(List.of(), null);
    }

    /**
     * @param connectUrlTemplate portal URL for {@code credential_required}, {@code {system}} replaced by the
     *                           system id; {@code null} leaves {@code connectUrl} out
     */
    public McpErrorMapper(List<ToolErrorMapping> mappings, String connectUrlTemplate) {
        this.mappings = List.copyOf(mappings);
        this.connectUrlTemplate = connectUrlTemplate;
    }

    public SafeError map(Throwable ex) {
        LOG.error("event=tool_failed chain=\"{}\" at=\"{}\"", describeChain(ex), innermostFrames(ex));
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            Optional<SafeError> mapped = classify(current);
            if (mapped.isPresent()) {
                return normalize(mapped.get());
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return new SafeError(ToolErrorCode.INTERNAL_ERROR.code(), GENERIC);
    }

    private Optional<SafeError> classify(Throwable ex) {
        for (ToolErrorMapping mapping : mappings) {
            Optional<SafeError> mapped = mapping.map(ex);
            if (mapped.isPresent()) {
                return mapped;
            }
        }
        if (ex instanceof SanitizedToolException sanitized) {
            return Optional.of(sanitized.toSafeError());
        }
        if (ex instanceof CredentialRequiredException missing) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("system", missing.getSystem());
            if (connectUrlTemplate != null) {
                details.put("connectUrl", connectUrlTemplate.replace("{system}", missing.getSystem()));
            }
            return Optional.of(new SafeError(ToolErrorCode.CREDENTIAL_REQUIRED.code(),
                    "Connect your " + missing.getSystem() + " account in the portal, then try again.", details));
        }
        if (ex instanceof InvalidToolArgumentException) {
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(),
                    echo(ex, INVALID_ARGUMENT_TEXT)));
        }
        if (ex instanceof IllegalArgumentException) {
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(), INVALID_ARGUMENT_TEXT));
        }
        if (ex instanceof SecurityException) {
            return Optional.of(new SafeError(ToolErrorCode.ACCESS_DENIED.code(), "Not authorized for this operation."));
        }
        if (ex instanceof ResourceAccessException || (ex instanceof RestClientResponseException response
                && response.getStatusCode().value() >= 502 && response.getStatusCode().value() <= 504)) {
            return Optional.of(new SafeError(ToolErrorCode.UPSTREAM_UNAVAILABLE.code(),
                    "The backend system is not available right now. Try again later."));
        }
        return Optional.empty();
    }

    private static SafeError normalize(SafeError error) {
        if (ToolErrorCode.of(error.code()).isEmpty()) {
            LOG.warn("event=tool_error_code_unknown code=\"{}\" detail=\"not a C10 code; sent as internal_error\"",
                    error.code());
            return new SafeError(ToolErrorCode.INTERNAL_ERROR.code(), GENERIC);
        }
        Set<String> allowed = DETAILS_BY_CODE.getOrDefault(error.code(), Set.of());
        Map<String, Object> details = new LinkedHashMap<>();
        error.details().forEach((key, value) -> {
            if (allowed.contains(key) && value != null) {
                details.put(key, value instanceof String text ? SecretRedactor.redact(text) : value);
            }
        });
        SafeError normalized = new SafeError(error.code(), truncate(SecretRedactor.redact(error.message())), details);
        if (!TOOL_ERROR_SCHEMA.validate(JSON.valueToTree(asObject(normalized))).isEmpty()) {
            LOG.warn("event=tool_error_invalid code=\"{}\" detail=\"does not match tool-error.schema.json; sent as "
                    + "internal_error\"", error.code());
            return new SafeError(ToolErrorCode.INTERNAL_ERROR.code(), GENERIC);
        }
        return normalized;
    }

    private static Map<String, Object> asObject(SafeError error) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("error", error.code());
        object.put("message", error.message());
        object.putAll(error.details());
        return object;
    }

    private static String echo(Throwable ex, String fallback) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static String truncate(String message) {
        if (message == null || message.isBlank()) {
            return GENERIC;
        }
        return message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE - 3) + "...";
    }

    /** {@code Outer: message <- Inner: message}, without response bodies and without the credential. */
    static String describeChain(Throwable ex) {
        List<String> levels = new ArrayList<>();
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            String text;
            if (current instanceof RestClientResponseException response) {
                text = current.getClass().getName() + ": HTTP " + response.getStatusCode().value() + " (body not logged)";
            } else {
                String message = current.getMessage();
                text = current.getClass().getName() + (message == null ? "" : ": " + SecretRedactor.redact(message));
            }
            levels.add(text.replace('"', '\'').replace('\n', ' ').replace('\r', ' '));
            current = current.getCause() == current ? null : current.getCause();
        }
        return String.join(" <- ", levels);
    }

    private static String innermostFrames(Throwable ex) {
        Throwable innermost = ex;
        for (int depth = 0; innermost.getCause() != null && innermost.getCause() != innermost
                && depth < MAX_CAUSE_DEPTH; depth++) {
            innermost = innermost.getCause();
        }
        StackTraceElement[] frames = innermost.getStackTrace();
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(LOGGED_FRAMES, frames.length); i++) {
            shown.add(frames[i].toString());
        }
        return String.join(" | ", shown);
    }
}
