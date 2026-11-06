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
 *
 * <p>This is the only way a tool becomes {@code DESTRUCTIVE} in the manifest: the server asks no confirmation
 * itself (the gateway does, with a TOTP code, C11), it only has to say truthfully that the tool needs one. The
 * platform keeps that statement consistent: the manifest {@code risk}, the {@code destructiveHint} of
 * {@code tools/list} and the audit all come from this annotation, a tool whose name says delete, drop, purge and
 * the like cannot be flagged milder, and a DESTRUCTIVE tool cannot declare the {@code read} or {@code update} action.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolRisk {

    RiskLevel value();

    /**
     * The Cedar action the server evaluates on the tool's resources (C6 {@code action}); by default the one the risk
     * implies: {@code read}, {@code update} or {@code delete}. Set {@link ToolAction#ADMIN} for an administrative
     * operation. A value that contradicts the risk stops the server ({@link ToolAction#resolve}).
     */
    ToolAction action() default ToolAction.AUTO;

    /**
     * A group a user needs <em>in addition to</em> the one the risk derives ({@code mcp-<system>-read}, {@code -write}, {@code -destructive}),
     * for a tool that only some users of the system may call: {@code mcp-<system>-<suffix>} with the system of this server and a suffix other
     * than {@code read}, {@code write} and {@code destructive}, for example {@code mcp-opensearch-ops}. It can only narrow access
     * ({@code platform-call-additional-group}); it goes to the manifest ({@code additionalGroup}) and into {@code definitionHash}, so a
     * change needs approval, and the server enforces it on every call as the gateway does. Empty: none. A value that does not fit the
     * rule stops the server.
     */
    String additionalGroup() default "";
}
