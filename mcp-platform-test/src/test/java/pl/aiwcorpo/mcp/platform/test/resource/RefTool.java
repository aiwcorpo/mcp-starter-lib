package pl.aiwcorpo.mcp.platform.test.resource;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import pl.aiwcorpo.mcp.platform.guard.RiskClassifiedTool;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolAction;
import pl.aiwcorpo.mcp.platform.resource.ResourceBinding;

/** A read tool with one resource argument, for tests of the policy on that argument. */
record RefTool(String name, String argument, String type, RiskLevel riskLevel, ToolAction action, List<ResourceBinding> resourceRefs)
        implements ToolCallback, RiskClassifiedTool {

    RefTool(String name, String argument, String type) {
        this(name, argument, type, RiskLevel.READ_ONLY, ToolAction.READ, List.of(new ResourceBinding(argument, type, Void.class)));
    }

    @Override
    public String title() {
        return "Get thing";
    }

    @Override
    public boolean resultWithoutResources() {
        return true;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder().name(name).description("d")
                .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}").build();
    }

    @Override
    public String call(String toolInput) {
        return "";
    }
}
