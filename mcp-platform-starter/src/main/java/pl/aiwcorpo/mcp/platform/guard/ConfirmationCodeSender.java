package pl.aiwcorpo.mcp.platform.guard;

import java.time.Instant;

/**
 * Delivers a one-time confirmation code to the human behind a call, over a channel the AI model
 * cannot read. That separation is the whole point: an agent that has been talked into a destructive
 * call by a poisoned document cannot also approve it.
 *
 * <p>The default implementation only writes to the server log and exists for demos. A real server
 * registers a bean that sends a push notification, an SMS or a message in the corporate chat.
 */
public interface ConfirmationCodeSender {

    /**
     * @param identity  who must approve — the recipient
     * @param tool      the tool about to run
     * @param summary   a short description of the call, safe to show to the recipient
     * @param code      the one-time code, formatted {@code 123-456}
     * @param expiresAt when the code stops working
     */
    void send(CallerIdentity identity, String tool, String summary, String code, Instant expiresAt);
}
