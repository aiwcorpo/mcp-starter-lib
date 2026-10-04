package pl.aiwcorpo.mcp.platform.observability;

import java.util.Map;
import java.util.function.Function;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;
import pl.aiwcorpo.mcp.platform.guard.CallerContext;
import pl.aiwcorpo.mcp.platform.guard.CallerIdentity;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolGuard;
import pl.aiwcorpo.mcp.platform.guard.ToolRejectedException;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;

/**
 * Decorates a {@link ToolCallback} so that every tool invocation is guarded, audited and every failure
 * is sanitized before it can reach the AI client. Applied automatically to all tools by
 * {@link ToolCallbackAuditPostProcessor} — servers do not wire this themselves.
 *
 * <p>This is the seam the platform was missing: {@code AuditLogger} and {@code McpErrorMapper} are
 * only useful if something calls them on the tool-invocation path, and Spring AI's MCP server offers
 * no such hook. It wraps the callback instead.
 *
 * <h2>Why sanitizing here is load-bearing</h2>
 * Spring AI's {@code McpToolUtils} catches {@link Exception} around {@code call(...)} and returns
 * {@code e.getMessage()} to the model as error content. An un-decorated tool that lets a
 * {@code RestClientException} escape therefore hands the model the backend URL and a slice of the
 * upstream response body. Here, only a {@link SanitizedToolException} ever escapes.
 *
 * <h2>What is recorded</h2>
 * Tool name, correlation id, outcome, latency, the number of argument characters and the set of
 * backend systems the caller supplied a PAT for. <b>Never the argument values and never a PAT.</b>
 * Arguments are model-authored free text (issue bodies, snapshot descriptions) and are not worth the
 * disclosure risk in a log that ships to a SIEM.
 *
 * <h2>The guard runs first</h2>
 * Since 2.0.0 a {@link ToolGuard}, when present, decides each call before the tool sees it: is the
 * tool enabled, may this caller use a tool of this risk, and has a human confirmed a destructive
 * call. A refusal is audited ({@code outcome=denied} or {@code confirmation_pending}) and the tool
 * is never invoked.
 *
 * <h2>Subject attribution</h2>
 * With the guard on, the subject is the caller resolved by the {@code IdentityFilter}
 * ({@code anonymous} when no credentials were presented). With the guard off it is
 * {@value #SUBJECT_UNAUTHENTICATED}: the server is then a pure bring-your-own-token passthrough and
 * does not know who the caller is; identity lives in the <em>backend's</em> audit log — join the two
 * on the correlation id.
 */
public class AuditingToolCallback implements ToolCallback {

    /** Guard off: no inbound authn exists; the backend's audit log holds the real identity. */
    public static final String SUBJECT_UNAUTHENTICATED = "-";

    private final ToolCallback delegate;
    private final AuditLogger auditLogger;
    private final McpErrorMapper errorMapper;
    private final ToolGuard guard;              // null = guard disabled
    private final ToolDefinition definition;

    public AuditingToolCallback(ToolCallback delegate, AuditLogger auditLogger, McpErrorMapper errorMapper) {
        this(delegate, auditLogger, errorMapper, null);
    }

    /**
     * @param guard the policy enforcement point, or {@code null} to audit and sanitize only. Passing a
     *              guard validates the tool immediately: an unflagged tool throws here, at startup.
     */
    public AuditingToolCallback(ToolCallback delegate, AuditLogger auditLogger, McpErrorMapper errorMapper,
                                ToolGuard guard) {
        this.delegate = delegate;
        this.auditLogger = auditLogger;
        this.errorMapper = errorMapper;
        this.guard = guard;
        if (guard == null) {
            this.definition = delegate.getToolDefinition();
        } else {
            guard.register(delegate);
            ToolDefinition original = delegate.getToolDefinition();
            this.definition = DefaultToolDefinition.builder()
                    .name(original.name())
                    .description(original.description())
                    .inputSchema(guard.publishedSchema(delegate))
                    .build();
        }
    }

    /** The undecorated tool, for platform code that needs to look past the decorator. */
    public ToolCallback getDelegate() {
        return delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return invoke(delegate::call, toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return invoke(input -> delegate.call(input, toolContext), toolInput, toolContext);
    }

    private String invoke(Function<String, String> action, String toolInput, ToolContext toolContext) {
        String tool = definition.name();
        long startNanos = System.nanoTime();

        String subject = SUBJECT_UNAUTHENTICATED;
        String risk = AuditLogger.NONE;
        String admittedInput = toolInput;
        String okReason = AuditLogger.NONE;
        if (guard != null) {
            try {
                ToolGuard.Admission admission = guard.admit(delegate, toolInput, toolContext);
                subject = admission.identity().subject();
                risk = admission.risk().label();
                admittedInput = admission.toolInput();
                okReason = admission.confirmed() ? "user_confirmed" : AuditLogger.NONE;
            } catch (ToolRejectedException rejected) {
                audit(tool, subjectOf(rejected.getIdentity()), labelOf(rejected.getRisk()), toolInput,
                        "confirmation_required".equals(rejected.getCode()) ? "confirmation_pending" : "denied",
                        rejected.getCode(), startNanos);
                throw rejected;
            } catch (RuntimeException ex) {
                // The guard itself failed. Fail closed: the tool does not run on an undecided call.
                audit(tool, currentSubject(), risk, toolInput, "error", "guard_error", startNanos);
                throw new SanitizedToolException(errorMapper.map(ex));
            }
        }

        try {
            String result = action.apply(admittedInput);
            audit(tool, subject, risk, toolInput, "ok", okReason, startNanos);
            return result;
        } catch (SanitizedToolException alreadySafe) {
            // A nested decorator already mapped and audited nothing; record the outcome and rethrow as-is.
            audit(tool, subject, risk, toolInput, "error", alreadySafe.getCode(), startNanos);
            throw alreadySafe;
        } catch (Exception ex) {
            McpErrorMapper.SafeError error = errorMapper.map(ex);   // maps AND logs full detail
            audit(tool, subject, risk, toolInput, "error", error.code(), startNanos);
            throw new SanitizedToolException(error);
        }
    }

    private void audit(String tool, String subject, String risk, String toolInput, String outcome,
                       String reason, long startNanos) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        Map<String, Object> redactedArgs = Map.of(
                "argsChars", toolInput == null ? 0 : toolInput.length(),
                "patSystems", CredentialContext.systems());
        auditLogger.toolInvoked(tool, subject, MDC.get(CorrelationIdFilter.MDC_KEY), risk,
                redactedArgs, outcome, reason, latencyMs);
    }

    private static String currentSubject() {
        return CallerContext.current().map(CallerIdentity::subject).orElse(CallerIdentity.ANONYMOUS_SUBJECT);
    }

    private static String subjectOf(CallerIdentity identity) {
        return identity == null ? CallerIdentity.ANONYMOUS_SUBJECT : identity.subject();
    }

    private static String labelOf(RiskLevel risk) {
        return risk == null ? AuditLogger.NONE : risk.label();
    }
}
