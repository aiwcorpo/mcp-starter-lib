package pl.aiwcorpo.mcp.platform.guard;

import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;

/**
 * The guard refused a call before the tool ran. A {@link SanitizedToolException}, so its message —
 * authored here, never by a backend — is what the AI client reads.
 *
 * <p>Codes: {@code unauthenticated}, {@code access_denied}, {@code tool_disabled},
 * {@code confirmation_required}, {@code confirmation_failed}, {@code confirmation_declined}.
 */
public class ToolRejectedException extends SanitizedToolException {

    private final transient CallerIdentity identity;
    private final RiskLevel risk;

    public ToolRejectedException(String code, String message, CallerIdentity identity, RiskLevel risk) {
        super(new McpErrorMapper.SafeError(code, message));
        this.identity = identity;
        this.risk = risk;
    }

    /** The caller as resolved at the time of the refusal; may be {@code null} if there was none. */
    public CallerIdentity getIdentity() {
        return identity;
    }

    public RiskLevel getRisk() {
        return risk;
    }
}
