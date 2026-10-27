package pl.aiwcorpo.mcp.platform.observability;

import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;

/**
 * A step around every tool call, run by {@link AuditingToolCallback} after the caller is bound to the tool and
 * inside the audit and the error mapping: whatever an interceptor throws is mapped to a C10 error and audited.
 *
 * <p>The extension point for decisions that need the tool's arguments or result, such as resolving a
 * {@code @ResourceRef} and asking the policy engine before the call, or dropping result items the policy does
 * not allow after it. Beans of this type are applied in {@code @Order}.
 */
@FunctionalInterface
public interface ToolCallInterceptor {

    /**
     * @param invocation the call being made
     * @param next       the rest of the chain and finally the tool; call it at most once, or not at all to refuse
     * @return the tool result to return to the client
     */
    String intercept(Invocation invocation, Next next);

    /**
     * @param tool      tool name
     * @param risk      the tool's risk flag
     * @param arguments JSON arguments as the client sent them
     * @param caller    the verified caller (never the service variant: it cannot call tools)
     */
    record Invocation(String tool, RiskLevel risk, String arguments, CallerContext caller) {
    }

    @FunctionalInterface
    interface Next {
        String proceed(String arguments);
    }
}
