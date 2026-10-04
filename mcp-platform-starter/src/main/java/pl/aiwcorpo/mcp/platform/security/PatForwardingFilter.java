package pl.aiwcorpo.mcp.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Extracts per-request PATs from inbound headers ({@code <prefix><system>}, e.g. {@code X-PAT-Jira})
 * into the request-scoped {@link CredentialContext}, then clears them after the request completes.
 *
 * <p>Bring-your-own-token model: each caller supplies their own PATs and the server forwards them to
 * the matching backend, so every user acts as themselves. PATs are secrets — this filter never logs
 * their values.
 */
public class PatForwardingFilter extends OncePerRequestFilter implements Ordered {

    /** After {@code CorrelationIdFilter}, before the handler that will read the PATs. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

    private final String headerPrefix;

    public PatForwardingFilter(String headerPrefix) {
        this.headerPrefix = headerPrefix;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Map<String, String> pats = new HashMap<>();
        String prefixLower = headerPrefix.toLowerCase(Locale.ROOT);
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            for (String name : Collections.list(names)) {
                if (name.toLowerCase(Locale.ROOT).startsWith(prefixLower)) {
                    String system = name.substring(headerPrefix.length()).toLowerCase(Locale.ROOT);
                    pats.put(system, request.getHeader(name));
                }
            }
        }
        CredentialContext.set(pats);
        try {
            chain.doFilter(request, response);
        } finally {
            CredentialContext.clear();
        }
    }
}
