package pl.aiwcorpo.mcp.platform.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.JsonNode;
import pl.aiwcorpo.mcp.platform.observability.AuditLogger;
import pl.aiwcorpo.mcp.platform.observability.CorrelationIdFilter;

/**
 * Admits only requests from the gateway: a valid internal JWT (C2) of the purpose of the endpoint, bound to this very request.
 * There is no client certificate on the server port (CC-249). Anything else gets 401, never an anonymous caller.
 *
 * <p>On success the verified caller is in {@link CallerContext} for the rest of the request. Infrastructure
 * failures (JWKS, Redis) refuse the request with 503: the token could not be judged, so it is not accepted.
 *
 * <p>A refused request leaves an {@code authentication} audit event (C8, CC-071, CC-120): the reasons the gateway
 * knows for a token of its own (a token of the wrong purpose is {@code wrong_purpose}) and, for the two infrastructure
 * failures, {@code jwks_unavailable} and {@code replay_store_unavailable} with {@code upstream_unavailable}.
 * Refusals for {@code missing_token} and {@code malformed} (a token that could not be parsed) have a per-process budget
 * each for the audit event and the log line ({@link MissingTokenEventLimiter}); the rest is not capped, including a token
 * whose header is refused after its {@code kid} resolved.
 *
 * <p>Paths in {@code unauthenticatedPaths} (by default the health endpoints) and {@code GET /.well-known/mcp-manifest} (C14: the
 * manifest is served without authentication, an {@code Authorization} header that arrives is ignored) are passed through untouched.
 * They are exact paths, never patterns, and a request is let through only when its raw URI is literally one
 * of them: no {@code ;}, {@code ..}, percent-encoding, backslash or double slash, compared case-sensitively.
 * Anything that the container might normalize to a different path is verified like every other request, so
 * {@code /actuator/health/..;/mcp} and similar spellings get 401 rather than reaching the MCP endpoint.
 */
public class InternalJwtFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(InternalJwtFilter.class);

    /** After the correlation id (10), before the credential (20). */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 15;

    private static final Set<String> METHODS_WITHOUT_BODY = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    /** Raw URI fragments a container may normalize away; such a request is never exempt from the check. */
    private static final Pattern SUSPICIOUS =
            Pattern.compile("(?i);|\\.\\.|%|\\\\|//");

    private final InternalJwtVerifier verifier;
    private final Set<String> unauthenticatedPaths;
    private final long maxRequestBody;
    private final AuditLogger audit;
    private final MissingTokenEventLimiter missingTokenEvents;

    /** Without an audit logger the refusals reach only the application log; for tests of the check itself. */
    public InternalJwtFilter(InternalJwtVerifier verifier, List<String> unauthenticatedPaths, long maxRequestBody) {
        this(verifier, unauthenticatedPaths, maxRequestBody, null);
    }

    /** As below, with the default cap on {@code missing_token} events and its counter in a registry of its own. */
    public InternalJwtFilter(InternalJwtVerifier verifier, List<String> unauthenticatedPaths, long maxRequestBody,
                             AuditLogger audit) {
        this(verifier, unauthenticatedPaths, maxRequestBody, audit,
                new MissingTokenEventLimiter(MissingTokenEventLimiter.DEFAULT_PER_SECOND, System::nanoTime,
                        new SimpleMeterRegistry()));
    }

    /**
     * @param audit              receives an {@code authentication} event (C8) for every refused request; may be {@code null}
     * @param missingTokenEvents decides which events for a request without a usable token are written and logged; the others are only counted
     */
    public InternalJwtFilter(InternalJwtVerifier verifier, List<String> unauthenticatedPaths, long maxRequestBody,
                             AuditLogger audit, MissingTokenEventLimiter missingTokenEvents) {
        this.audit = audit;
        this.missingTokenEvents = missingTokenEvents;
        this.verifier = verifier;
        for (String path : unauthenticatedPaths) {
            if (!path.startsWith("/") || SUSPICIOUS.matcher(path).find() || path.contains("*")) {
                throw new IllegalArgumentException("unauthenticated paths must be exact paths, got '" + path + "'");
            }
        }
        this.unauthenticatedPaths = Set.copyOf(unauthenticatedPaths);
        this.maxRequestBody = maxRequestBody;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String raw = request.getRequestURI();
        String contextPath = request.getContextPath() == null ? "" : request.getContextPath();
        if (raw == null || !raw.startsWith(contextPath)) {
            return false;
        }
        String rawPath = raw.substring(contextPath.length());
        if (SUSPICIOUS.matcher(rawPath).find()) {
            return false;
        }
        String normalized = normalizedPath(request);
        if (!rawPath.equals(normalized)) {
            return false;
        }
        return unauthenticatedPaths.contains(normalized)
                || ("GET".equals(request.getMethod()) && McpRequestClassifier.MANIFEST_PATH.equals(normalized));
    }

    /**
     * Whether a request this filter let through is a JSON-RPC {@code initialize}, read from the body it buffered. The
     * transport opens a session for every {@code initialize}, with or without an {@code Mcp-Session-Id}. A request whose
     * body this filter did not buffer counts as one, so a caller that limits sessions errs on the side of refusing.
     */
    public static boolean isInitialize(ServletRequest request) {
        ServletRequest current = request;
        while (!(current instanceof CachedBodyRequest) && current instanceof ServletRequestWrapper wrapper) {
            current = wrapper.getRequest();
        }
        if (!(current instanceof CachedBodyRequest buffered)) {
            return true;
        }
        JsonNode message = StrictJson.parse(buffered.body());
        JsonNode method = message == null ? null : message.get("method");
        return method != null && method.isString() && "initialize".equals(method.asString());
    }

    /** The path the container resolved: servlet path plus path info, as routing sees it. */
    static String normalizedPath(HttpServletRequest request) {
        String servletPath = request.getServletPath() == null ? "" : request.getServletPath();
        String pathInfo = request.getPathInfo() == null ? "" : request.getPathInfo();
        return servletPath + pathInfo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        List<String> authorization = Collections.list(request.getHeaders("Authorization"));
        if (authorization.isEmpty()) {
            reject(request, response, InternalJwtRejection.MISSING_TOKEN, null, start);
            return;
        }
        String token = authorization.size() == 1 ? bearerToken(authorization.getFirst()) : null;
        if (token == null) {
            reject(request, response, InternalJwtRejection.MALFORMED, null, start);
            return;
        }

        CachedBodyRequest buffered;
        try {
            buffered = METHODS_WITHOUT_BODY.contains(request.getMethod())
                    ? null : CachedBodyRequest.read(request, maxRequestBody);
        } catch (CachedBodyRequest.BodyTooLargeException ex) {
            writeError(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, badRequestCode(request), null,
                    "The request body is too large.");
            return;
        }
        HttpServletRequest forwarded = buffered == null ? request : buffered;
        RequestBinding binding;
        try {
            binding = McpRequestClassifier.classify(request.getMethod(), normalizedPath(request),
                    buffered == null ? new byte[0] : buffered.body(), singleHeader(request, CorrelationIdFilter.HEADER));
        } catch (McpRequestClassifier.InvalidRequestException ex) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, badRequestCode(request), null,
                    "The request body must be a single JSON object.");
            return;
        }

        CallerContext caller;
        try {
            caller = verifier.verify(token, binding);
        } catch (InternalJwtRejectedException ex) {
            reject(request, response, ex.reason(), binding, start);
            return;
        }
        CallerContext.bind(caller);
        try {
            chain.doFilter(forwarded, response);
        } finally {
            CallerContext.clear();
        }
    }

    private static String bearerToken(String header) {
        if (header.length() < 7 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        List<String> values = Collections.list(request.getHeaders(name));
        return values.size() == 1 ? values.getFirst() : null;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, InternalJwtRejection reason,
                        RequestBinding binding, long start) throws IOException {
        String kind = binding == null ? "-" : binding.kind().name().toLowerCase(Locale.ROOT);
        // One decision for the log line and the audit event: a flood without a usable token fills neither.
        boolean report = !MissingTokenEventLimiter.covers(reason) || missingTokenEvents.tryAcquire(reason);
        if (reason.infrastructure()) {
            if (report) {
                LOG.error("event=internal_jwt_unverifiable reason={} method={} path=\"{}\" kind={}", reason.code(),
                        request.getMethod(), normalizedPath(request), kind);
                audit(reason, "upstream_unavailable", start);
            }
            response.setHeader("Retry-After", "5");
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "upstream_unavailable", null,
                    "The request could not be authenticated right now. Try again later.");
            return;
        }
        if (report) {
            LOG.warn("event=internal_jwt_rejected reason={} method={} path=\"{}\" kind={}", reason.code(),
                    request.getMethod(), normalizedPath(request), kind);
            audit(reason, "unauthenticated", start);
        }
        response.setHeader("WWW-Authenticate",
                reason == InternalJwtRejection.MISSING_TOKEN ? "Bearer" : "Bearer error=\"invalid_token\"");
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthenticated",
                wellKnown(request) ? reason.code() : null, "The request was not authenticated by the platform gateway.");
    }

    private void audit(InternalJwtRejection reason, String errorCode, long start) {
        if (audit != null) {
            audit.authenticationDenied(reason.code(), errorCode, (System.nanoTime() - start) / 1_000_000L);
        }
    }

    private static boolean wellKnown(HttpServletRequest request) {
        return normalizedPath(request).startsWith("/.well-known/");
    }

    /** C14 names the refusal of a body on the well-known paths {@code invalid_request}; the MCP endpoint keeps {@code invalid_argument}. */
    private static String badRequestCode(HttpServletRequest request) {
        return wellKnown(request) ? "invalid_request" : "invalid_argument";
    }

    /**
     * The 401 of a well-known path carries the C2 reason, as C14 documents (the gateway pulls and pushes there, and it is the one that needs
     * to know why); on the MCP endpoint the reason stays in the log and the audit event, as before, because a caller that is not the gateway
     * learns nothing from it that helps.
     */
    private static void writeError(HttpServletResponse response, int status, String code, String reason, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Fixed texts only: nothing from the request is echoed.
        response.getWriter().write("{\"error\":\"" + code + "\"" + (reason == null ? "" : ",\"reason\":\"" + reason + "\"")
                + ",\"message\":\"" + message + "\"}");
    }
}
