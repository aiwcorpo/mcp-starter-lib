package pl.aiwcorpo.mcp.platform.guard;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;

/**
 * "Who am I?" — resolves the caller once per request and publishes it in {@link CallerContext}.
 *
 * <p>Outcomes:
 * <ul>
 *   <li>valid access token → the caller it names, with what the policy grants their roles;</li>
 *   <li>no credentials → the anonymous caller at the policy's {@code anonymous} level (read-only by
 *       default), or HTTP 401 when the policy says {@code anonymous: deny};</li>
 *   <li>invalid credentials → HTTP 401, never a silent downgrade to anonymous.</li>
 * </ul>
 */
public class IdentityFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(IdentityFilter.class);

    /** After the correlation id (10) and PAT (20) filters, before the security headers (30). */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 25;

    public static final String MDC_KEY = "subject";

    private final IdentityResolver resolver;
    private final GuardPolicyStore policies;
    private final boolean advertiseMetadata;
    private final boolean forwardAccessToken;

    public IdentityFilter(IdentityResolver resolver, GuardPolicyStore policies) {
        this(resolver, policies, false, false);
    }

    /**
     * @param advertiseMetadata whether a 401 should point the client at the protected-resource
     *                          metadata, i.e. whether an identity provider is configured
     * @param forwardAccessToken OAuth mode: keep the caller's validated access token for the request so
     *                           outbound adapters can act for them in the backend
     */
    public IdentityFilter(IdentityResolver resolver, GuardPolicyStore policies, boolean advertiseMetadata,
                          boolean forwardAccessToken) {
        this.resolver = resolver;
        this.policies = policies;
        this.advertiseMetadata = advertiseMetadata;
        this.forwardAccessToken = forwardAccessToken;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    /** Health and metrics probes carry no caller; they are protected by network placement, not by us. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith(request.getContextPath() + "/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CallerIdentity identity;
        try {
            Optional<CallerIdentity> resolved = resolver.resolve(request);
            if (resolved.isPresent()) {
                identity = resolved.get();
            } else {
                RiskLevel anonymous = policies.current().anonymousMaxRisk();
                if (anonymous == null) {
                    reject(request, response, null, "Authentication is required.");
                    return;
                }
                identity = CallerIdentity.anonymous(anonymous);
            }
        } catch (IdentityResolver.InvalidCredentialsException ex) {
            LOG.warn("event=authentication_failed reason=\"{}\" path=\"{}\"", ex.getMessage(), request.getRequestURI());
            reject(request, response, "invalid_token", ex.getMessage());
            return;
        }
        CallerContext.set(identity);
        MDC.put(MDC_KEY, identity.subject());
        if (forwardAccessToken && identity.authenticated()) {
            // Only ever a token the resolver has just validated.
            OidcIdentityResolver.bearerToken(request).ifPresent(CredentialContext::setAccessToken);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            CredentialContext.clearAccessToken();
            MDC.remove(MDC_KEY);
            CallerContext.clear();
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String error, String message)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        StringBuilder challenge = new StringBuilder("Bearer");
        if (error != null) {
            challenge.append(" error=\"").append(error).append('"');
        }
        if (advertiseMetadata) {
            challenge.append(error == null ? " " : ", ").append("resource_metadata=\"")
                    .append(ProtectedResourceMetadataFilter.metadataUrl(request)).append('"');
        }
        response.setHeader("WWW-Authenticate", challenge.toString());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // message is one of our own fixed strings — never echoes the presented credential
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"" + message + "\"}");
    }
}
