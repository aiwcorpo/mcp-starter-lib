package pl.aiwcorpo.mcp.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * In the {@code dev} profile the internal JWT may be signed with the published test keys, so a valid token does not say who sent it,
 * and the loopback listener is reachable by every local account and, through DNS rebinding, by a web page. This filter admits only
 * requests whose {@code Host} is a loopback name and whose {@code Origin}, when present, is one too: the rule {@code LocalGateway}
 * applies on its own port. Outside {@code dev} it passes everything; the gateway addresses a deployed server by any name.
 */
public class DevLocalPeerFilter extends OncePerRequestFilter implements Ordered {

    /** After the correlation id (10), before the internal JWT (15). */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 12;

    private static final Pattern LOOPBACK_HOST = Pattern.compile("(?i)(127\\.0\\.0\\.1|localhost|\\[::1\\])(:\\d+)?");
    private static final Pattern LOOPBACK_ORIGIN = Pattern.compile("(?i)https?://(127\\.0\\.0\\.1|localhost|\\[::1\\])(:\\d+)?");

    private final boolean dev;

    public DevLocalPeerFilter(boolean dev) {
        this.dev = dev;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !dev;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getHeader("Host");
        String origin = request.getHeader("Origin");
        if (host == null || !LOOPBACK_HOST.matcher(host).matches()
                || origin != null && !LOOPBACK_ORIGIN.matcher(origin).matches()) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"error\":\"forbidden\",\"message\":\"In the dev profile only local clients are served: "
                    + "Host and Origin must be 127.0.0.1, localhost or [::1].\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
