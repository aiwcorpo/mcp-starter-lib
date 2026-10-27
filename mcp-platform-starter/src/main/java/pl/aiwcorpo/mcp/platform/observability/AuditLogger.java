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
