package pl.aiwcorpo.mcp.platform.test.resource;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import pl.aiwcorpo.mcp.platform.guard.RiskClassifiedTool;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolAction;
import pl.aiwcorpo.mcp.platform.resource.ResourceBinding;

/** A read tool without resource arguments, for tests of the policy that decides on the tool alone. */
record ReadThingTool(String name, RiskLevel riskLevel, ToolAction action, List<ResourceBinding> resourceRefs)
        implements ToolCallback, RiskClassifiedTool {

    ReadThingTool() {
        this("read_thing", RiskLevel.READ_ONLY, ToolAction.READ, List.of());
    }

    @Override
    public String title() {
        return "Read thing";
    }

    @Override
    public boolean resultWithoutResources() {
        return true;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DefaultToolDefinition.builder().name("read_thing").description("d")
                .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}").build();
    }

    @Override
    public String call(String toolInput) {
        return "";
    }
}
