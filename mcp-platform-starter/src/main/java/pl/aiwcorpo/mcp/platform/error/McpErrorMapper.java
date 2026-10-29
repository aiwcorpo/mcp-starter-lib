package pl.aiwcorpo.mcp.platform.error;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import pl.aiwcorpo.mcp.platform.text.ToolResultRejectedException;
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
 * {@code not_found_or_denied} always carries the one text of the contract, whatever a server mapping wrote (CC-146). A code
 * outside C10 becomes {@code internal_error}; fields C10 does not define for the code are dropped;
 * the message is cut to 500 characters; a result that still does not match {@code tool-error.schema.json}
 * (e.g. a required field missing) becomes {@code internal_error}. The current request's credential is removed from every message.
 *
 * <h2>What the log gets</h2>
 * One {@code event=tool_failed} line per failure, at INFO for an expected outcome (C10 code and exception class
 * only), WARN for an unavailable backend and ERROR for {@code internal_error}; the last two carry the exception
 * classes and messages of the chain (credential removed), ERROR also the top frames of the innermost exception.
 * Bodies of backend error responses are not logged: they are the place where backends echo request headers.
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
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            Optional<SafeError> mapped = classify(current);
            if (mapped.isPresent()) {
                SafeError error = normalize(mapped.get());
                logFailure(ex, current, error);
                return error;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        SafeError error = new SafeError(ToolErrorCode.INTERNAL_ERROR.code(), GENERIC);
        logFailure(ex, ex, error);
        return error;
    }

    /**
     * One {@code event=tool_failed} line per failed call, at the level the failure deserves: an expected outcome
     * (the caller's mistake, a missing credential, a refusal, a resource that is not there or not allowed) is
     * INFO with the C10 code and the exception class only, so ordinary model errors do not page anybody and no
     * exception text is kept; a backend that is down is WARN with the chain; only what the server did not
     * anticipate ({@code internal_error}) is ERROR, with the chain and the top frames.
     */
    private static void logFailure(Throwable ex, Throwable matched, SafeError error) {
        String code = error.code();
        if (ToolErrorCode.INTERNAL_ERROR.code().equals(code)) {
            LOG.error("event=tool_failed code={} chain=\"{}\" at=\"{}\"", code, describeChain(ex), innermostFrames(ex));
        } else if (ToolErrorCode.UPSTREAM_UNAVAILABLE.code().equals(code)) {
            LOG.warn("event=tool_failed code={} chain=\"{}\"", code, describeChain(ex));
        } else {
            LOG.info("event=tool_failed code={} exception={}", code, matched.getClass().getName());
        }
    }

    private Optional<SafeError> classify(Throwable ex) {
        if (ex instanceof ToolResultRejectedException) {
            // Before the server's mappings: a result the platform rejected is never a denial, whatever the server maps.
            return Optional.of(new SafeError(ToolErrorCode.INTERNAL_ERROR.code(), GENERIC));
        }
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
        String message = ToolErrorCode.NOT_FOUND_OR_DENIED.code().equals(error.code())
                ? ToolErrorCode.NOT_FOUND_OR_DENIED_MESSAGE : truncate(SecretRedactor.redact(error.message()));
        SafeError normalized = new SafeError(error.code(), message, details);
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

    /**
     * {@code Outer: message <- Inner: message}, without the credential. When the chain holds an exception of the
     * HTTP client, of the message converters or of Jackson (an HTTP error, a 200 body that does not parse), no
     * message of the chain is logged at all, only classes and the HTTP status: these messages quote the response
     * body, wrappers (Spring AI's {@code ToolExecutionException}, the server's own) copy them, and the body is
     * where backends echo request headers and return data the log must not keep.
     */
    static String describeChain(Throwable ex) {
        boolean httpResponseInChain = false;
        Throwable probe = ex;
        for (int depth = 0; probe != null && depth < MAX_CAUSE_DEPTH; depth++) {
            httpResponseInChain |= quotesResponse(probe);
            probe = probe.getCause() == probe ? null : probe.getCause();
        }
        List<String> levels = new ArrayList<>();
        Throwable current = ex;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            String text;
            if (current instanceof RestClientResponseException response) {
                text = current.getClass().getName() + ": HTTP " + response.getStatusCode().value() + " (body not logged)";
            } else if (httpResponseInChain) {
                text = current.getClass().getName() + " (message not logged: may quote the HTTP response)";
            } else {
                String message = current.getMessage();
                text = current.getClass().getName() + (message == null ? "" : ": " + SecretRedactor.redact(message));
            }
            levels.add(oneSafeLine(text));
            current = current.getCause() == current ? null : current.getCause();
        }
        return String.join(" <- ", levels);
    }

    private static final Pattern URL_QUERY = Pattern.compile("(https?://[^\\s?#\"']*)\\?[^\\s\"']*");

    /**
     * One log-safe line: control, bidi and zero-width characters removed ({@link TextHygiene#normalize}), line
     * breaks and tabs turned into spaces, quotes softened so the value cannot close the {@code chain="..."}
     * field, and the query of any URL left out (it carries the model's arguments, e.g. a JQL expression).
     */
    private static String oneSafeLine(String text) {
        String clean = TextHygiene.normalize(text).replace('\n', ' ').replace('\t', ' ').replace('"', '\'');
        return URL_QUERY.matcher(clean).replaceAll("$1?<query not logged>");
    }

    /** Packages whose exception messages quote response bodies or parsed documents. */
    private static final List<String> RESPONSE_QUOTING_PACKAGES = List.of("org.springframework.web.client.",
            "org.springframework.http.converter.", "com.fasterxml.jackson.", "tools.jackson.");

    private static boolean quotesResponse(Throwable ex) {
        String name = ex.getClass().getName();
        return RESPONSE_QUOTING_PACKAGES.stream().anyMatch(name::startsWith);
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
