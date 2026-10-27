package pl.aiwcorpo.mcp.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the request id of the call ({@code X-Request-Id}, C3) into the logging MDC and echoes it in the
 * response, so application logs, audit events and the gateway's records join on one {@code requestId}.
 *
 * <p>The header is taken only when it is a UUIDv7 as the contracts define it; anything else (or nothing)
 * gets a fresh id, so a forged value cannot inject text into log lines. This filter does not decide whether
 * the id is trustworthy: {@code InternalJwtFilter} rejects a request whose header differs from the token's
 * {@code rid}, so on every request that reaches a tool the MDC value equals {@code rid}.
 */
public class CorrelationIdFilter extends OncePerRequestFilter implements Ordered {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        String requestId = RequestIds.isValid(header) ? header : RequestIds.newRequestId();
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
