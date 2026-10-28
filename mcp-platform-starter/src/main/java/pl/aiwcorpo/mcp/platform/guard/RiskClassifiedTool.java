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

    /**
     * Whether the result holds no resources the policy must filter, or the callback filters them itself: the declaration a
     * {@code @Tool} method makes with {@code @ResultWithoutResources}. A hand-written callback has no method whose result type the
     * platform could read, so without this declaration it stops the server (it would return unfiltered resources).
     */
    default boolean resultWithoutResources() {
        return false;
    }

    /** The title shown by clients; required, a tool without one stops the server. */
    default String title() {
        return null;
    }
}
