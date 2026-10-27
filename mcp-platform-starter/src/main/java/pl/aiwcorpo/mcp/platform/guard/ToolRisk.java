package pl.aiwcorpo.mcp.platform.guard;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares how dangerous a {@code @Tool} method is. Mandatory: a server whose tool carries no flag
 * does not start (see {@code aiwcorpo.mcp.platform.tools.unclassified}).
 *
 * <pre>{@code
 * @Tool(name = "order_cancel", description = "Cancel an order.")
 * @ToolRisk(RiskLevel.UPDATE)
 * public Order cancel(String id) { ... }
 * }</pre>
 *
 * <p>The flag is set by the developer in code and reviewed like code. It goes to the manifest ({@code risk},
 * C6), on which the gateway decides who may call the tool and whether a human must confirm it, to the audit
 * (C8), and to the {@code readOnlyHint}/{@code destructiveHint} the MCP client is shown. No model is asked.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolRisk {

    RiskLevel value();
}
