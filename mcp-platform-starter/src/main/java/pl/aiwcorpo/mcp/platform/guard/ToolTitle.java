package pl.aiwcorpo.mcp.platform.guard;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The human-readable name of a {@code @Tool} method that MCP clients show, {@code title} of the tool definition. Mandatory
 * since the contracts 3.0.0 (CC-115): a server whose tool carries none does not start. The title is part of the
 * {@code definitionHash} the gateway pins, so changing it is a change of the tool definition.
 *
 * <pre>{@code
 * @Tool(name = "get_issue", description = "Returns an issue by key.")
 * @ToolRisk(RiskLevel.READ_ONLY)
 * @ToolTitle("Get issue")
 * public IssueView getIssue(...) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ToolTitle {

    /** Non-blank text without the characters that the text hygiene refuses (CC-121), at most {@value ToolRiskRegistry#MAX_TITLE_LENGTH} characters. */
    String value();
}
