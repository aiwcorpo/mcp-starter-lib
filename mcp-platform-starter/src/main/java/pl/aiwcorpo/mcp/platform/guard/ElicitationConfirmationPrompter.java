package pl.aiwcorpo.mcp.platform.guard;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;

/**
 * Prompts through MCP <em>elicitation</em>: the server asks the client to show a form to the user
 * and blocks until they answer. The request and the answer travel between the client's UI and this
 * server; the model is not in that loop.
 *
 * <p>Only used when the client advertised the elicitation capability at initialize time.
 */
public class ElicitationConfirmationPrompter implements ConfirmationPrompter {

    private static final Logger LOG = LoggerFactory.getLogger(ElicitationConfirmationPrompter.class);

    private static final Map<String, Object> FORM = Map.of(
            "type", "object",
            "properties", Map.of("code", Map.of(
                    "type", "string",
                    "title", "Confirmation code",
                    "description", "The one-time code you received, e.g. 123-456")),
            "required", List.of("code"));

    @Override
    public Optional<Reply> prompt(ToolContext toolContext, String message) {
        if (toolContext == null) {
            return Optional.empty();
        }
        Optional<McpSyncServerExchange> exchange = McpToolUtils.getMcpExchange(toolContext);
        if (exchange.isEmpty()) {
            return Optional.empty();
        }
        McpSchema.ClientCapabilities capabilities = exchange.get().getClientCapabilities();
        if (capabilities == null || capabilities.elicitation() == null) {
            return Optional.empty();
        }
        try {
            McpSchema.ElicitResult result = exchange.get().createElicitation(
                    McpSchema.ElicitFormRequest.builder(message, FORM).build());
            if (result == null || result.action() != McpSchema.ElicitResult.Action.ACCEPT) {
                return Optional.of(new Reply(false, null));
            }
            Object code = result.content() == null ? null : result.content().get("code");
            return Optional.of(new Reply(true, code == null ? null : String.valueOf(code)));
        } catch (RuntimeException ex) {
            // Timed out, client went away, client refused the request: all of them mean "not confirmed".
            LOG.warn("event=confirmation_prompt_failed reason=\"{}\"", ex.toString());
            return Optional.of(new Reply(false, null));
        }
    }
}
