package pl.aiwcorpo.mcp.platform.guard;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Publishes each tool's risk flag to MCP clients as the standard tool annotations
 * ({@code readOnlyHint}, {@code destructiveHint}), so a client can show its own warning before it
 * even sends a destructive call, and its {@link ToolTitle} as the {@code title} of the definition.
 *
 * <p>Spring AI builds the MCP tool list from {@code ToolCallback}s and has no place for these
 * annotations, so this rewrites the finished list. The hints are advice to the client and nothing
 * more: enforcement does not depend on the client honouring them.
 *
 * <p>Because it sees every finished tool list, this is also where a tool that reached the MCP server
 * without passing through the platform decorator is caught, and the server stopped.
 */
public class ToolHintsPostProcessor implements BeanPostProcessor {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ObjectProvider<ToolRiskRegistry> registry;

    public ToolHintsPostProcessor(ObjectProvider<ToolRiskRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        String single = toolNameOf(bean);
        if (single != null) {
            ToolRiskRegistry toolRegistry = registry.getIfAvailable();
            if (toolRegistry == null || !toolRegistry.isRegistered(single)) {
                throw new IllegalStateException("MCP tool '" + single + "' (bean '" + beanName + "') is a tool "
                        + "specification bean and does not go through the platform's tool decorator. Declare tools "
                        + "with @Tool + @ToolRisk and expose them through a ToolCallbackProvider.");
            }
            return bean;
        }
        if (!(bean instanceof List<?> list) || list.isEmpty()) {
            return bean;
        }
        ToolRiskRegistry toolRegistry = registry.getIfAvailable();
        if (toolRegistry == null) {
            return bean;
        }
        boolean allSync = true;
        for (Object item : list) {
            String name = toolNameOf(item);
            if (name == null) {
                return bean;                      // not a list of MCP tool specifications
            }
            if (!toolRegistry.isRegistered(name)) {
                // By the time Spring AI has built a tool list, every tool that came through a
                // ToolCallback has been registered by the audit decorator. One that has not took another
                // road (in practice Spring AI's own @McpTool) and would run unflagged, unaudited and without
                // the tool binding check. There is no safe way to let that start.
                throw new IllegalStateException("MCP tool '" + name + "' (bean '" + beanName + "') does not go "
                        + "through the platform's tool decorator. Declare tools with @Tool + @ToolRisk and expose "
                        + "them through a ToolCallbackProvider; @McpTool-annotated tools are not supported.");
            }
            allSync &= item instanceof SyncToolSpecification;
        }
        if (!allSync) {
            return bean;                          // registered, but a spec type we do not add hints to
        }
        List<SyncToolSpecification> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(withHints((SyncToolSpecification) item, toolRegistry));
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

    private static SyncToolSpecification withHints(SyncToolSpecification specification, ToolRiskRegistry toolRegistry) {
        McpSchema.Tool tool = specification.tool();
        RiskLevel risk = toolRegistry.riskOf(tool.name()).orElse(null);
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
        Optional<ObjectNode> outputSchema = toolRegistry.outputSchemaOf(tool.name());
        Map<String, Object> published = tool.outputSchema() != null || outputSchema.isEmpty() ? tool.outputSchema()
                : JSON.convertValue(outputSchema.get(), new TypeReference<Map<String, Object>>() { });
        McpSchema.Tool annotated = new McpSchema.Tool(tool.name(), toolRegistry.titleOf(tool.name()).orElse(tool.title()),
                tool.description(),
                tool.inputSchema(), published, hints, tool.meta(), tool.icons());
        return new SyncToolSpecification(annotated,
                outputSchema.isPresent() ? withStructuredContent(specification.callHandler()) : specification.callHandler());
    }

    /**
     * MCP requires a tool with an output schema to return the result as {@code structuredContent}; Spring AI
     * returns text content only, so the JSON text of a successful result is repeated there. An error result
     * (C10) is left as it is.
     */
    private static BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult>
            withStructuredContent(BiFunction<McpSyncServerExchange, McpSchema.CallToolRequest, McpSchema.CallToolResult> handler) {
        return (exchange, request) -> {
            McpSchema.CallToolResult result = handler.apply(exchange, request);
            if (Boolean.TRUE.equals(result.isError()) || result.structuredContent() != null
                    || result.content() == null || result.content().size() != 1
                    || !(result.content().getFirst() instanceof McpSchema.TextContent text)) {
                return result;
            }
            Object structured;
            try {
                structured = JSON.readValue(text.text(), Object.class);
            } catch (JacksonException ex) {
                throw new IllegalStateException("the result of a tool with an output schema is not JSON", ex);
            }
            return McpSchema.CallToolResult.builder().content(result.content()).isError(false)
                    .structuredContent(structured).meta(result.meta()).build();
        };
    }
}
