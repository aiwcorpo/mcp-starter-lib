package pl.aiwcorpo.mcp.platform.error;

/**
 * The caller's arguments are wrong, with a message the server wrote for the model so it can correct the call
 * ({@code invalid_argument}, C10). The only exception whose message reaches the client unchanged (apart from
 * {@link SanitizedToolException}): never build the message from a backend response or from a secret.
 *
 * <p>A plain {@link IllegalArgumentException}, which libraries throw with whatever text they have at hand, is
 * also reported as {@code invalid_argument}, but with a fixed message.
 */
public class InvalidToolArgumentException extends IllegalArgumentException {

    public InvalidToolArgumentException(String message) {
        super(message);
    }
}
