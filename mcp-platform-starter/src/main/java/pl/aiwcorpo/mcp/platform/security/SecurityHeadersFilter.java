package pl.aiwcorpo.mcp.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Baseline HTTP hardening headers (HSTS, nosniff, frame-deny). A plain servlet filter; the platform does
 * not use Spring Security. HTTPS itself is enforced by the server
 * connector ({@code server.ssl}); this just tells clients not to downgrade or frame the responses.
 */
public class SecurityHeadersFilter extends OncePerRequestFilter implements Ordered {

    /** First, so that refusals of the later platform filters (401, 400) carry the headers too. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

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
