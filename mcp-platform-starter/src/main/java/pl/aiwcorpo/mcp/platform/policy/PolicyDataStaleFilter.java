package pl.aiwcorpo.mcp.platform.policy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.auth.InternalJwtFilter;

/**
 * The fail-closed gate of the policy data, at the door of {@code tools/call} (C14): a server that holds no policy data, or older than the
 * {@code prv} of the token, answers {@code 409 policy_data_stale} before it does anything else with the request, and ahead of every other
 * refusal of the gate. The gateway then pushes its data and repeats the call once with a new token.
 *
 * <p>Runs right after {@link InternalJwtFilter}, so only an authenticated call reaches it, and decides with the same rule as the
 * check inside the tool ({@link PolicyEngine#gate}); that one stays as the second line for a push that raced the call.
 */
final class PolicyDataStaleFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger LOG = LoggerFactory.getLogger(PolicyDataStaleFilter.class);

    /** Right after the internal JWT filter, before the credential filter. */
    static final int ORDER = InternalJwtFilter.ORDER + 1;

    static final String STALE = "policy_data_stale";

    private final PolicyEngine engine;

    PolicyDataStaleFilter(PolicyEngine engine) {
        this.engine = engine;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CallerContext caller = CallerContext.current().orElse(null);
        // Only a tools/call carries the tool claim (C2); the other requests of the gateway have nothing to evaluate.
        if (caller == null || caller.tool() == null) {
            chain.doFilter(request, response);
            return;
        }
        PolicyEngine.Gated gated = engine.gate(caller.policyRevision() > 0 ? OptionalLong.of(caller.policyRevision()) : OptionalLong.empty());
        if (!STALE.equals(gated.decision().reason())) {
            chain.doFilter(request, response);
            return;
        }
        LOG.info("event=policy_data_stale held={} required={}", gated.heldRevision().isPresent() ? gated.heldRevision().getAsLong() : "none",
                gated.requiredRevision().isPresent() ? gated.requiredRevision().getAsLong() : "none");
        response.setStatus(HttpServletResponse.SC_CONFLICT);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // requiredRevision is the prv of the token as it is; 0 (a gateway without policy data) when the server holds nothing.
        long required = caller.policyRevision();
        response.getWriter().write("{\"error\":\"" + STALE + "\",\"heldRevision\":"
                + (gated.heldRevision().isPresent() ? Long.toString(gated.heldRevision().getAsLong()) : "null")
                + ",\"requiredRevision\":" + required + "}");
    }
}
