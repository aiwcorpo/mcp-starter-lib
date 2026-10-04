package pl.aiwcorpo.mcp.platform.guard;

/**
 * For tools that are not {@code @Tool} methods (hand-written {@code ToolCallback}s): implement this
 * on the callback to declare its risk, since there is no method to put {@link ToolRisk} on.
 */
public interface RiskClassifiedTool {

    RiskLevel riskLevel();
}
