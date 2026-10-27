package pl.aiwcorpo.mcp.platform.server;

import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import io.modelcontextprotocol.server.McpServer;

/**
 * The one {@link McpSyncServerCustomizer} Spring AI accepts, owned by the starter.
 *
 * <p>Spring AI injects a single customizer and, when there is one, skips what it otherwise does itself:
 * {@code immediateExecution(true)}, which keeps a tool on the request thread where {@code CallerContext} and
 * {@code CredentialContext} live. A server that registered its own customizer therefore lost that setting
 * silently (every call ran without a credential). This customizer applies the server's customizers first
 * (every other {@link McpSyncServerCustomizer} bean, in {@code @Order}) and the platform's settings last, so
 * a server can add to the MCP server but cannot switch off what the platform relies on:
 * <ul>
 *   <li>{@code immediateExecution(true)}: tools run on the request thread;</li>
 * </ul>
 */
public class PlatformMcpServerCustomizer implements McpSyncServerCustomizer {

    private final ObjectProvider<McpSyncServerCustomizer> serverCustomizers;

    public PlatformMcpServerCustomizer(ObjectProvider<McpSyncServerCustomizer> serverCustomizers) {
        this.serverCustomizers = serverCustomizers;
    }

    @Override
    public void customize(McpServer.SyncSpecification<?> server) {
        serverCustomizers.orderedStream().filter(customizer -> customizer != this)
                .forEach(customizer -> customizer.customize(server));
        server.immediateExecution(true);
    }
}
