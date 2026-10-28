package pl.aiwcorpo.mcp.platform.observability;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;
import pl.aiwcorpo.mcp.platform.error.ToolErrorCode;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.input.ToolInputPolicy;
import pl.aiwcorpo.mcp.platform.input.ToolInputValidator;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;
import pl.aiwcorpo.mcp.platform.text.ToolResultRejectedException;
import pl.aiwcorpo.mcp.platform.text.ToolResultValidator;

/**
 * Wraps every tool the server exposes (applied by {@link ToolCallbackAuditPostProcessor}, servers do not wire
 * it). Per call:
 * <ol>
 *   <li><b>Binding.</b> The call runs only for a verified user caller whose internal JWT names this tool
 *       ({@code tool} claim). {@code InternalJwtFilter} already compared it with {@code params.name}; this
 *       repeats the check where the tool actually runs, so a transport path the filter did not classify, or
 *       a tool invoked without a request, is refused ({@code unauthenticated}) instead of running.</li>
 *   <li><b>Arguments.</b> Validated against the tool's {@code inputSchema} ({@link ToolInputValidator}); a
 *       violation is {@code invalid_argument} (C10) without the offending value. The MCP SDK's own validation
 *       is off because it answers before this class, with a text that is not C10 and no audit event.</li>
 *   <li><b>Interceptors</b> ({@link ToolCallInterceptor}), then the tool.</li>
 *   <li><b>Result.</b> A result larger than {@code tools.max-result-size} is {@code internal_error} before it is
 *       parsed. A tool that publishes an {@code outputSchema} ({@code UntrustedText} in its result) has
 *       its result checked against it ({@link ToolResultValidator}) before the audit event is written; a
 *       mismatch is {@code internal_error} and {@code outcome=error}, not an SDK error after an {@code ok} event.</li>
 *   <li><b>Errors.</b> Whatever escapes is mapped by {@link McpErrorMapper}; only a
 *       {@link SanitizedToolException} carrying a C10 error object leaves this class. Spring AI puts an
 *       exception's message verbatim into the tool result, so an unmapped {@code RestClientException} would
 *       hand the model the backend URL and response body.</li>
 *   <li><b>Audit.</b> One {@code tool_call} event (C8) per call, refused calls included.</li>
 * </ol>
 * Registering the tool here also validates its risk flag, so an unflagged tool stops the server at startup.
 */
public final class AuditingToolCallback implements ToolCallback {

    private static final Set<String> DENIAL_CODES = Set.of(ToolErrorCode.UNAUTHENTICATED.code(),
            ToolErrorCode.ACCESS_DENIED.code(), ToolErrorCode.NOT_FOUND_OR_DENIED.code(),
            ToolErrorCode.TOOL_DISABLED.code());

    private final ToolCallback delegate;
    private final AuditLogger auditLogger;
    private final McpErrorMapper errorMapper;
    private final List<ToolCallInterceptor> interceptors;
    private final RiskLevel risk;
    private final ToolInputValidator inputValidator;
    private final ToolResultValidator resultValidator;
    private final long maxResultBytes;

    /**
     * Not public: a wrapper built by a server would carry its own interceptors and input policy and then be taken
     * for the platform's. Only {@link ToolCallbackAuditPostProcessor} creates it, with the policy of the profile.
     */
    AuditingToolCallback(ToolCallback delegate, AuditLogger auditLogger, McpErrorMapper errorMapper,
                         ToolRiskRegistry registry, List<ToolCallInterceptor> interceptors) {
        this(delegate, auditLogger, errorMapper, registry, interceptors, ToolInputPolicy.STRICT,
                McpPlatformProperties.DEFAULT_MAX_RESULT_SIZE.toBytes());
    }

    AuditingToolCallback(ToolCallback delegate, AuditLogger auditLogger, McpErrorMapper errorMapper,
                         ToolRiskRegistry registry, List<ToolCallInterceptor> interceptors,
                         ToolInputPolicy inputPolicy, long maxResultBytes) {
        this.maxResultBytes = maxResultBytes;
        this.delegate = delegate;
        this.auditLogger = auditLogger;
        this.errorMapper = errorMapper;
        this.interceptors = List.copyOf(interceptors);
        this.risk = registry.register(delegate);
        this.inputValidator = ToolInputValidator.compile(delegate.getToolDefinition(), inputPolicy);
        this.resultValidator = registry.outputSchemaOf(delegate.getToolDefinition().name())
                .map(schema -> ToolResultValidator.compile(delegate.getToolDefinition().name(), schema)).orElse(null);
    }

    /** The undecorated tool. */
    public ToolCallback getDelegate() {
        return delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return invoke(toolInput, delegate::call);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return invoke(toolInput, input -> delegate.call(input, toolContext));
    }

    private String invoke(String toolInput, UnaryOperator<String> tool) {
        String name = delegate.getToolDefinition().name();
        long start = System.nanoTime();
        Optional<CallerContext> bound = CallerContext.current()
                .filter(caller -> !caller.isService() && name.equals(caller.tool()));
        if (bound.isEmpty()) {
            SanitizedToolException refused = new SanitizedToolException(new McpErrorMapper.SafeError(
                    ToolErrorCode.UNAUTHENTICATED.code(), "The call was not authorized for this tool by the platform gateway."));
            audit(CallerContext.current().orElse(null), name, toolInput, refused.getCode(), start);
            throw refused;
        }
        CallerContext caller = bound.get();
        try {
            inputValidator.validate(toolInput);
            String result = chain(0, new ToolCallInterceptor.Invocation(name, risk, toolInput, caller), tool)
                    .proceed(toolInput);
            checkSize(name, result);
            if (resultValidator != null) {
                resultValidator.validate(result);
            }
            audit(caller, name, toolInput, null, start);
            return result;
        } catch (Exception ex) {
            McpErrorMapper.SafeError error = errorMapper.map(ex);
            audit(caller, name, toolInput, error.code(), start);
            throw new SanitizedToolException(error);
        }
    }

    private void checkSize(String name, String result) {
        if (result == null || result.length() * 3L <= maxResultBytes) {
            return;
        }
        long bytes = result.length() > maxResultBytes ? result.length() : result.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxResultBytes) {
            throw new ToolResultRejectedException("The result of tool '" + name + "' is larger than the limit of "
                    + maxResultBytes + " bytes (aiwcorpo.mcp.platform.tools.max-result-size); return a page of results.");
        }
    }

    private ToolCallInterceptor.Next chain(int index, ToolCallInterceptor.Invocation invocation,
                                           UnaryOperator<String> tool) {
        if (index == interceptors.size()) {
            return tool::apply;
        }
        return arguments -> interceptors.get(index).intercept(
                new ToolCallInterceptor.Invocation(invocation.tool(), invocation.risk(), arguments, invocation.caller()),
                chain(index + 1, invocation, tool));
    }

    private void audit(CallerContext caller, String tool, String toolInput, String errorCode, long start) {
        String outcome = errorCode == null ? "ok" : DENIAL_CODES.contains(errorCode) ? "denied" : "error";
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        long argsLength = toolInput == null ? 0 : toolInput.getBytes(StandardCharsets.UTF_8).length;
        String credentialType = CredentialContext.type().map(BackendCredential.Type::wireName).orElse(null);
        auditLogger.toolCall(caller, tool, risk, credentialType, outcome, errorCode, latencyMs, argsLength);
    }
}
