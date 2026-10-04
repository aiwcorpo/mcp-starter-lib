package pl.aiwcorpo.mcp.platform.observability;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared, structured audit logger for tool invocations (auto-configured, injectable).
 * Logs who called which tool, how risky it was, what the guard decided, the outcome and latency.
 *
 * <p>NEVER log secrets/PII in clear — redact at the call site before passing {@code redactedArgs}.
 * Route the configured logger name to a dedicated audit appender -&gt; SIEM.
 */
public class AuditLogger {

    /** Placeholder for a field that does not apply to a record. */
    public static final String NONE = "-";

    private final Logger log;

    public AuditLogger(String loggerName) {
        this.log = LoggerFactory.getLogger(loggerName);
    }

    public void toolInvoked(String tool, String subject, String correlationId,
                            Map<String, Object> redactedArgs, String outcome, long latencyMs) {
        toolInvoked(tool, subject, correlationId, NONE, redactedArgs, outcome, NONE, latencyMs);
    }

    /**
     * @param risk    the tool's risk level in force for this call ({@code read-only}, {@code update},
     *                {@code destructive}), or {@value #NONE} when the guard is off
     * @param outcome {@code ok}, {@code error}, {@code denied} (the guard refused the call) or
     *                {@code confirmation_pending} (waiting for the human's code)
     * @param reason  machine-readable code behind the outcome, e.g. {@code access_denied}; for an
     *                {@code ok} call that a human approved it is {@code user_confirmed}
     */
    public void toolInvoked(String tool, String subject, String correlationId, String risk,
                            Map<String, Object> redactedArgs, String outcome, String reason, long latencyMs) {
        log.info("event=tool_invoked tool=\"{}\" subject=\"{}\" cid=\"{}\" risk=\"{}\" outcome=\"{}\" reason=\"{}\" "
                        + "latencyMs={} args={}",
                tool, subject, correlationId, risk, outcome, reason, latencyMs, redactedArgs);
    }
}
