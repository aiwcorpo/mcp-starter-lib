package pl.aiwcorpo.mcp.platform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

/**
 * Refuses to start a server whose MCP transport cannot carry the caller's credentials.
 *
 * <h2>The failure this prevents</h2>
 * {@link CredentialContext} is a {@link ThreadLocal}, filled by {@link PatForwardingFilter} on the
 * servlet thread and read by outbound adapters. That works because the synchronous MCP server runs
 * a tool on the very thread that received the request. Switch the transport to
 * {@code spring.ai.mcp.server.type=ASYNC} and the handler runs on a Reactor scheduler instead — a
 * different thread, where the ThreadLocal is empty. Every tool then behaves as if the caller had
 * sent no PAT at all: not a crash, not a warning, just a server that is authenticated to nothing.
 *
 * <p>That is the worst shape a fault can take — silent, total, and indistinguishable from a client
 * that forgot a header. A configuration flip in a YAML file must not be able to do it, so the
 * server refuses to start instead.
 *
 * <h2>The related trap this does NOT catch</h2>
 * Even in SYNC mode, the synchronous server only stays on the request thread because Spring AI's
 * servlet stack sets {@code immediateExecution(true)} on its tool specifications. A server that
 * registers its own {@code McpSyncServerCustomizer} and forgets to repeat that setting pushes tool
 * handlers onto {@code Schedulers.boundedElastic()} and loses the ThreadLocal in exactly the same
 * way. There is no property to inspect for that, so it is covered by a test instead —
 * {@code CredentialPassthroughIT} in this module calls a real tool over the real transport and
 * asserts the credential arrives.
 */
public class CredentialTransportGuard implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialTransportGuard.class);

    private static final String TYPE_PROPERTY = "spring.ai.mcp.server.type";

    private final Environment environment;
    private final boolean required;

    public CredentialTransportGuard(Environment environment, boolean required) {
        this.environment = environment;
        this.required = required;
    }

    @Override
    public void afterPropertiesSet() {
        String type = environment.getProperty(TYPE_PROPERTY, "SYNC");
        if (!"ASYNC".equalsIgnoreCase(type.trim())) {
            return;
        }
        String explanation = TYPE_PROPERTY + "=ASYNC is incompatible with per-request credentials: "
                + "CredentialContext is a ThreadLocal filled on the servlet thread, and an async "
                + "handler runs on a different one, so every tool would silently act with no PAT. "
                + "Use SYNC, or set aiwcorpo.mcp.platform.require-sync-transport=false if this server "
                + "genuinely reaches no credentialed backend.";
        if (required) {
            throw new IllegalStateException(explanation);
        }
        LOG.warn("event=credential_transport_unsafe detail=\"{}\"", explanation);
    }
}
