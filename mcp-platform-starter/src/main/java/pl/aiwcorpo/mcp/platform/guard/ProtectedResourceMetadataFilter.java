package pl.aiwcorpo.mcp.platform.guard;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Serves OAuth 2.0 Protected Resource Metadata (RFC 9728) at
 * {@value #WELL_KNOWN}: the document that tells an MCP client which authorization server issues
 * tokens for this server. With it, a client that gets a 401 can find the provider and start the
 * login itself instead of needing a token pasted into its configuration.
 *
 * <p>This server only points at the provider. The login, consent and token endpoints are the
 * provider's.
 */
public class ProtectedResourceMetadataFilter extends OncePerRequestFilter implements Ordered {

    public static final String WELL_KNOWN = "/.well-known/oauth-protected-resource";

    /** Before {@link IdentityFilter}: the metadata must be readable without a token. */
    public static final int ORDER = IdentityFilter.ORDER - 1;

    private final String issuer;
    private final String configuredResource;
    private final String mcpEndpoint;

    /**
     * @param configuredResource the public URL of the MCP endpoint, or {@code null} to derive it
     *                           from the request (honouring {@code X-Forwarded-*})
     */
    public ProtectedResourceMetadataFilter(String issuer, String configuredResource, String mcpEndpoint) {
        this.issuer = issuer;
        this.configuredResource = configuredResource == null || configuredResource.isBlank() ? null : configuredResource;
        this.mcpEndpoint = mcpEndpoint;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // RFC 9728 allows the resource's path to be appended after the well-known segment.
        if (issuer == null || issuer.isBlank() || !"GET".equals(request.getMethod())
                || !(path.equals(WELL_KNOWN) || path.startsWith(WELL_KNOWN + "/"))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"resource\":" + json(resource(request))
                + ",\"authorization_servers\":[" + json(issuer) + "]"
                + ",\"bearer_methods_supported\":[\"header\"]}");
    }

    String resource(HttpServletRequest request) {
        return configuredResource != null ? configuredResource : baseUrl(request) + mcpEndpoint;
    }

    /** Where clients should look for this document; used in the {@code WWW-Authenticate} header. */
    static String metadataUrl(HttpServletRequest request) {
        return baseUrl(request) + WELL_KNOWN;
    }

    /** Public origin of this server as the client sees it, i.e. the gateway's when there is one. */
    private static String baseUrl(HttpServletRequest request) {
        String scheme = firstValue(request.getHeader("X-Forwarded-Proto"));
        String host = firstValue(request.getHeader("X-Forwarded-Host"));
        if (scheme == null) {
            scheme = request.getScheme();
        }
        if (host == null) {
            host = request.getHeader("Host");
        }
        if (host == null) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        // Only what can appear in an origin; anything else in a header is not echoed back.
        if (!scheme.matches("https?") || !host.matches("[A-Za-z0-9.\\-\\[\\]:]{1,255}")) {
            return request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
        }
        return scheme + "://" + host + request.getContextPath();
    }

    private static String firstValue(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        return header.split(",")[0].trim();
    }

    private static String json(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
