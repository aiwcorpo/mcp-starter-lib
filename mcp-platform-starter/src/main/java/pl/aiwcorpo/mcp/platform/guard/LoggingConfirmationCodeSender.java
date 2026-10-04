package pl.aiwcorpo.mcp.platform.guard;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DEMO delivery channel: prints the code to the server log, on its own logger so it can be routed
 * (or switched off) independently. Anyone who can read this log can approve destructive calls —
 * replace this bean before the server guards anything real.
 */
public class LoggingConfirmationCodeSender implements ConfirmationCodeSender {

    public static final String LOGGER_NAME = "pl.aiwcorpo.mcp.confirmation";

    private static final Logger LOG = LoggerFactory.getLogger(LOGGER_NAME);

    @Override
    public void send(CallerIdentity identity, String tool, String summary, String code, Instant expiresAt) {
        // The summary (tool arguments) is deliberately not logged: arguments are model-authored text
        // and the platform keeps them out of every log. A real channel shows it to the approver.
        LOG.warn("event=confirmation_code_issued channel=demo-log to=\"{}\" tool=\"{}\" code={} expiresAt={}",
                identity.subject(), tool, code, expiresAt);
    }
}
