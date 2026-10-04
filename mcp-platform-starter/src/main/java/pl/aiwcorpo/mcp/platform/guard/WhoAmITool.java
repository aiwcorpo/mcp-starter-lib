package pl.aiwcorpo.mcp.platform.guard;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

/**
 * Built-in read-only tool {@value #NAME}: tells the caller who the server thinks they are and which
 * tools they may use. It makes the first two questions of every call — "Who am I?" and "Do I have
 * the privilege?" — answerable up front, instead of by trial and refusal.
 *
 * <p>It reveals nothing a caller could not find out by calling each tool and reading the refusals.
 */
public class WhoAmITool implements ToolCallback, RiskClassifiedTool {

    public static final String NAME = "platform_whoami";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final ToolDefinition DEFINITION = DefaultToolDefinition.builder()
            .name(NAME)
            .description("Returns the identity this server resolved for the caller, the highest risk level "
                    + "they may invoke, and for each tool whether they may call it and whether it needs "
                    + "human confirmation. Read-only.")
            .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
            .build();

    private final ToolGuard guard;

    public WhoAmITool(ToolGuard guard) {
        this.guard = guard;
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.READ_ONLY;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return DEFINITION;
    }

    @Override
    public String call(String toolInput) {
        CallerIdentity identity = CallerContext.current().or(guard::anonymousCaller)
                .orElseThrow(() -> new SecurityException("no caller identity"));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("subject", identity.subject());
        answer.put("authenticated", identity.authenticated());
        answer.put("maxRisk", identity.maxRisk().label());
        List<Map<String, Object>> tools = guard.permissionsFor(identity).stream().map(permission -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("tool", permission.tool());
            entry.put("risk", permission.risk().label());
            entry.put("allowed", permission.allowed());
            entry.put("needsConfirmation", permission.needsConfirmation());
            if (permission.denialCode() != null) {
                entry.put("denied", permission.denialCode());
            }
            return entry;
        }).toList();
        answer.put("tools", tools);
        return JSON.writeValueAsString(answer);
    }
}
