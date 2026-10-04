package pl.aiwcorpo.mcp.platform.guard;

import java.util.Optional;
import org.springframework.ai.chat.model.ToolContext;

/**
 * Asks the human, in the middle of a tool call, for the confirmation code ("wait for interaction").
 * Separated from {@link ToolGuard} so the decision logic can be tested without an MCP session.
 */
public interface ConfirmationPrompter {

    /** What came back from the human. */
    record Reply(boolean accepted, String code) {
    }

    /**
     * @return the reply, or empty when this client cannot be prompted (no interactive channel) and
     *         the guard must fall back to the two-call flow
     */
    Optional<Reply> prompt(ToolContext toolContext, String message);
}
