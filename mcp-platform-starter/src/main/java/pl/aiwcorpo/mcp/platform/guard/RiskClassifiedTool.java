package pl.aiwcorpo.mcp.platform.guard;

/**
 * For tools that are not {@code @Tool} methods (hand-written {@code ToolCallback}s): implement this
 * on the callback to declare what {@link ToolRisk}, {@link ToolAction} and {@link ToolTitle} declare on a method.
 */
public interface RiskClassifiedTool {

    RiskLevel riskLevel();

    /** The Cedar action; {@link ToolAction#AUTO} takes it from the risk. */
    default ToolAction action() {
        return ToolAction.AUTO;
    }

    /** The arguments that carry resource identifiers (what {@code @ResourceRef} declares on a method). */
    default java.util.List<pl.aiwcorpo.mcp.platform.resource.ResourceBinding> resourceRefs() {
        return java.util.List.of();
    }

    /** The title shown by clients; required, a tool without one stops the server. */
    default String title() {
        return null;
    }
}
