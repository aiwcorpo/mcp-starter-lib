package pl.aiwcorpo.mcp.platform.guard;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Publishes each tool's risk flag to MCP clients as the standard tool annotations
 * ({@code readOnlyHint}, {@code destructiveHint}), so a client can show its own warning before it
 * even sends a destructive call.
 *
 * <p>Spring AI builds the MCP tool list from {@code ToolCallback}s and has no place for these
 * annotations, so this rewrites the finished list. The hints are advice to the client and nothing
 * more: enforcement is {@link ToolGuard}'s job and does not depend on the client honouring them.
 *
 * <p>Because it sees every finished tool list, this is also where a tool that reached the MCP server
 * without passing through the guard is caught, and the server stopped.
 */
public class ToolHintsPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<ToolGuard> guard;

    public ToolHintsPostProcessor(ObjectProvider<ToolGuard> guard) {
        this.guard = guard;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof List<?> list) || list.isEmpty()) {
            return bean;
        }
        ToolGuard toolGuard = guard.getIfAvailable();
        if (toolGuard == null) {
            return bean;
        }
        boolean allSync = true;
        for (Object item : list) {
            String name = toolNameOf(item);
            if (name == null) {
                return bean;                      // not a list of MCP tool specifications
            }
            if (!toolGuard.isRegistered(name)) {
                // By the time Spring AI has built a tool list, every tool that came through a
                // ToolCallback has been validated by the guard. One that has not took another road —
                // in practice Spring AI's own @McpTool — and would be callable by anyone, unflagged,
                // unaudited. There is no safe way to let that start.
                throw new IllegalStateException("MCP tool '" + name + "' (bean '" + beanName + "') is not "
                        + "protected by the platform guard. Declare tools with @Tool + @ToolRisk and expose them "
                        + "through a ToolCallbackProvider; @McpTool-annotated tools bypass the guard and are "
                        + "not supported while aiwcorpo.mcp.platform.guard.enabled=true.");
            }
            allSync &= item instanceof SyncToolSpecification;
        }
        if (!allSync) {
            return bean;                          // guarded, but a spec type we do not add hints to
        }
        List<SyncToolSpecification> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(withHints((SyncToolSpecification) item, toolGuard));
        }
        return result;
    }

    /** The tool name if {@code item} is any flavour of MCP tool specification, else {@code null}. */
    private static String toolNameOf(Object item) {
        if (item instanceof SyncToolSpecification s) {
            return s.tool().name();
        }
        if (item instanceof McpServerFeatures.AsyncToolSpecification s) {
            return s.tool().name();
        }
        if (item instanceof McpStatelessServerFeatures.SyncToolSpecification s) {
            return s.tool().name();
        }
        if (item instanceof McpStatelessServerFeatures.AsyncToolSpecification s) {
            return s.tool().name();
        }
        return null;
    }

    private static SyncToolSpecification withHints(SyncToolSpecification specification, ToolGuard toolGuard) {
        McpSchema.Tool tool = specification.tool();
        // By the time Spring AI has built this list, the decorator has validated every tool in it.
        RiskLevel risk = toolGuard.registeredRisk(tool.name()).orElse(null);
        if (risk == null) {
            return specification;   // not one of ours (e.g. declared with Spring AI's own @McpTool)
        }
        McpSchema.ToolAnnotations existing = tool.annotations();
        McpSchema.ToolAnnotations hints = new McpSchema.ToolAnnotations(
                existing == null ? null : existing.title(),
                risk == RiskLevel.READ_ONLY,
                risk == RiskLevel.DESTRUCTIVE,
                existing == null ? null : existing.idempotentHint(),
                existing == null ? null : existing.openWorldHint(),
                existing == null ? null : existing.returnDirect());
        McpSchema.Tool annotated = new McpSchema.Tool(tool.name(), tool.title(), tool.description(),
                tool.inputSchema(), tool.outputSchema(), hints, tool.meta(), tool.icons());
        return new SyncToolSpecification(annotated, specification.callHandler());
    }
}
