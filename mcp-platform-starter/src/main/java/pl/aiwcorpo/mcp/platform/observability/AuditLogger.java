package pl.aiwcorpo.mcp.platform.observability;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes audit events (C8, {@code schemas/audit-event.schema.json}): one JSON object per line on a dedicated
 * logger, routed to the SIEM appender.
 *
 * <p>Never argument values, tool results, credentials or confirmation codes: the event carries only the
 * fields listed in C8, and {@code argsLength} is the only trace of the arguments. Every character outside
 * printable ASCII is written as a JSON unicode escape, which covers the control characters, U+2028/U+2029 and
 * bidi controls the schema requires to be escaped.
 */
public class AuditLogger {

    private static final JsonMapper JSON = JsonMapper.builder(
            JsonFactory.builder().highestNonEscapedChar(0x7E).build()).build();

    private final Logger log;
    private final String componentId;
    private final Clock clock;

    /**
     * @param loggerName  logger the lines go to
     * @param componentId this server's id ({@code mcp-<system>}), {@code componentId} and {@code server}
     */
    public AuditLogger(String loggerName, String componentId) {
        this(loggerName, componentId, Clock.systemUTC());
    }

    AuditLogger(String loggerName, String componentId, Clock clock) {
        this.log = LoggerFactory.getLogger(loggerName);
        this.componentId = componentId;
        this.clock = clock;
    }

    /**
     * {@code event: tool_call} for one tool invocation on this server.
     *
     * @param caller         the verified caller, or {@code null} when the call had none (refused)
     * @param risk           the tool's risk
     * @param credentialType {@code X-MCP-Credential-Type} of the request, or {@code null}
     * @param outcome        {@code ok}, {@code error} or {@code denied}
     * @param errorCode      C10 code, {@code null} for {@code ok}
     * @param argsLength     length in bytes of the serialized arguments
     */
    public void toolCall(CallerContext caller, String tool, RiskLevel risk, String credentialType, String outcome,
                         String errorCode, long latencyMs, long argsLength) {
        Map<String, Object> event = base("tool_call", caller);
        event.put("server", componentId);
        event.put("tool", tool);
        if (risk != null) {
            event.put("risk", risk.name());
        }
        if (caller != null) {
            event.put("policyRevision", caller.policyRevision());
        }
        if (credentialType != null) {
            event.put("credentialType", credentialType);
        }
        event.put("outcome", outcome);
        event.put("errorCode", errorCode);
        event.put("latencyMs", Math.max(0, latencyMs));
        event.put("argsLength", Math.max(0, argsLength));
        write(event);
    }

    /**
     * {@code event: policy_decision} for a denial made on this server: the resource or tool the policy refused (C8,
     * {@code server-resource-denied}). Allowed calls are covered by {@code tool_call}.
     *
     * @param resourceType Cedar type of the resource, or {@code null} for a decision on the tool itself
     * @param resourceId   canonical id, or {@code null}; left out when it contains characters C8 does not allow in a log field
     * @param parent       {@code <Namespace>::<Type>::<id>} of the parent, or {@code null}
     * @param reason       {@code policy_forbid} (the policy denied) or {@code not_granted} (not resolved, no resource)
     * @param revision     revision of the bundle that decided
     */
    public void policyDenied(CallerContext caller, String tool, RiskLevel risk, String resourceType, String resourceId,
                             String parent, String reason, String revision, String errorCode) {
        Map<String, Object> event = base("policy_decision", caller);
        event.put("server", componentId);
        event.put("tool", tool);
        if (risk != null) {
            event.put("risk", risk.name());
        }
        if (resourceType != null && resourceId != null && !resourceId.isEmpty() && resourceId.length() <= 512
                && TextHygiene.firstProblem(resourceId, true) == null) {
            Map<String, Object> resource = new LinkedHashMap<>();
            resource.put("type", resourceType);
            resource.put("id", resourceId);
            if (parent != null && parent.length() <= 600 && TextHygiene.firstProblem(parent, true) == null) {
                resource.put("parent", parent);
            }
            event.put("resource", resource);
        }
        event.put("decision", "deny");
        event.put("reason", reason);
        if (revision != null) {
            event.put("policyRevision", revision);
        }
        event.put("outcome", "denied");
        event.put("errorCode", errorCode);
        write(event);
    }

    /**
     * {@code event: policy_decision} for a request that could not be decided (CC-141, CC-142): no policy bundle is loaded
     * ({@code policy_unavailable}) or the policy engine failed on it ({@code policy_error}). The caller gets {@code upstream_unavailable}.
     */
    public void policyUnavailable(CallerContext caller, String tool, RiskLevel risk, String reason, long argsLength) {
        policyRefused(caller, tool, risk, reason, "upstream_unavailable", argsLength);
    }

    /** {@code policy_decision} with {@code deny} and {@code outcome: error} for a call refused because of the server's own state or configuration. */
    public void policyRefused(CallerContext caller, String tool, RiskLevel risk, String reason, String errorCode, long argsLength) {
        Map<String, Object> event = base("policy_decision", caller);
        event.put("server", componentId);
        event.put("tool", tool);
        if (risk != null) {
            event.put("risk", risk.name());
        }
        event.put("decision", "deny");
        event.put("reason", reason);
        event.put("outcome", "error");
        event.put("errorCode", errorCode);
        event.put("argsLength", Math.max(0, argsLength));
        write(event);
    }

    /**
     * {@code event: authentication} (C8, CC-071): a request refused before any caller was established. The
     * schema allows only the fields written here: nothing taken from the unverified token (not even
     * {@code sub}) and no part of the token itself.
     *
     * @param reason    wire code of the refusal ({@code InternalJwtRejection#code()}), a snake case word
     * @param errorCode C10 code of the HTTP answer: {@code unauthenticated} (401) or {@code upstream_unavailable}
     *                  (503, the token could not be judged)
     */
    public void authenticationDenied(String reason, String errorCode, long latencyMs) {
        Map<String, Object> event = base("authentication", null);
        event.put("server", componentId);
        event.put("decision", "deny");
        event.put("reason", reason);
        event.put("outcome", "denied");
        event.put("errorCode", errorCode);
        event.put("latencyMs", Math.max(0, latencyMs));
        write(event);
    }

    /** The fields every event has, plus the caller's identity when there is one. */
    protected Map<String, Object> base(String eventType, CallerContext caller) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ts", Instant.now(clock).truncatedTo(ChronoUnit.MILLIS).toString());
        event.put("requestId", requestId(caller));
        event.put("component", "server");
        event.put("componentId", componentId);
        event.put("event", eventType);
        if (caller != null) {
            event.put("sub", caller.subject());
            if (caller.username() != null) {
                event.put("username", caller.username());
            }
            if (caller.client() != null) {
                event.put("client", caller.client());
            }
            if (caller.virtualServer() != null) {
                event.put("virtualServer", caller.virtualServer());
            }
        }
        return event;
    }

    /** Writes one event as one line. */
    protected void write(Map<String, Object> event) {
        log.info(JSON.writeValueAsString(event));
    }

    private static String requestId(CallerContext caller) {
        if (caller != null) {
            return caller.requestId();
        }
        String fromMdc = MDC.get(CorrelationIdFilter.MDC_KEY);
        return RequestIds.isValid(fromMdc) ? fromMdc : RequestIds.newRequestId();
    }
}
