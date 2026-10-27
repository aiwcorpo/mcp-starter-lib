package pl.aiwcorpo.mcp.platform.security;

/**
 * Removes the current request's credential from text that is about to be logged or returned. Used by the
 * error mapper on exception messages, which is where secrets end up in practice: a backend echoing the
 * {@code Authorization} header in its error body, or a client library quoting the request.
 */
public final class SecretRedactor {

    public static final String REDACTED = "[REDACTED]";

    /** Shorter values would redact ordinary words; real tokens and passwords are longer. */
    private static final int MINIMUM_LENGTH = 4;

    private SecretRedactor() {
    }

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : CredentialContext.secrets()) {
            if (secret.length() >= MINIMUM_LENGTH) {
                result = result.replace(secret, REDACTED);
            }
        }
        return result;
    }
}
