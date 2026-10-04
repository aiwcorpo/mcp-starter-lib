package pl.aiwcorpo.mcp.platform.guard;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares how dangerous a {@code @Tool} method is. Mandatory: a server whose tool carries no flag
 * does not start (see {@code aiwcorpo.mcp.platform.guard.unclassified}).
 *
 * <pre>{@code
 * @Tool(name = "order_cancel", description = "Cancel an order.")
 * @ToolRisk(RiskLevel.UPDATE)
 * public Order cancel(String id) { ... }
 * }</pre>
 *
 * <p>The flag is set by the developer in code and reviewed like code. It is the deterministic input
 * to everything else: who may call the tool, whether a human must confirm the call, and the
 * {@code readOnlyHint}/{@code destructiveHint} the MCP client is shown. No model is asked.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolRisk {

    RiskLevel value();
}
