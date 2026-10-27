package pl.aiwcorpo.mcp.platform.guard;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Last check at startup: every tool the MCP server will list went through the platform's decorator
 * ({@code AuditingToolCallback}), which is where the risk flag, the tool binding, the C8 audit, the C10 error
 * mapping and the credential redaction happen. A tool that reached the server another way (Spring AI's
 * {@code @McpTool}, a hand-made tool specification, {@code addTool} at startup) stops the server.
 */
public class RegisteredToolsCheck implements SmartInitializingSingleton {

    private final ObjectProvider<McpSyncServer> servers;
    private final ToolRiskRegistry registry;

    public RegisteredToolsCheck(ObjectProvider<McpSyncServer> servers, ToolRiskRegistry registry) {
        this.servers = servers;
        this.registry = registry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        servers.orderedStream().forEach(server -> {
            List<String> foreign = server.listTools().stream().map(McpSchema.Tool::name)
                    .filter(name -> !registry.isRegistered(name)).toList();
            if (!foreign.isEmpty()) {
                throw new IllegalStateException("MCP tools " + foreign + " do not go through the platform's tool "
                        + "decorator (no @ToolRisk check, audit, C10 errors or credential redaction). Declare tools "
                        + "with @Tool + @ToolRisk and expose them through a ToolCallbackProvider; @McpTool and "
                        + "tool specification beans are not supported.");
            }
        });
    }
}
