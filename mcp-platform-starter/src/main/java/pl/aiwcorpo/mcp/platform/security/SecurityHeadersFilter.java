package pl.aiwcorpo.mcp.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Baseline HTTP hardening headers (HSTS, nosniff, frame-deny). A plain servlet filter, so the
 * platform needs no Spring Security once OAuth was removed. HTTPS itself is enforced by the server
 * connector ({@code server.ssl}); this just tells clients not to downgrade or frame the responses.
 */
public class SecurityHeadersFilter extends OncePerRequestFilter implements Ordered {

    /** Last of the platform filters; it only decorates the response. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        chain.doFilter(request, response);
    }
}
