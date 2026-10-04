package pl.aiwcorpo.mcp.platform.security;

/**
 * The caller did not supply a PAT for a backend this tool needs.
 *
 * <p>Distinct from a generic {@link SecurityException} because the message is actionable and safe to
 * surface: the client must retry with the named {@code X-PAT-<system>} header. A plain
 * "not authorized" would leave the caller unable to tell that it simply forgot a header.
 *
 * <p>The message must name only the <em>header</em>, never a token value.
 */
public class MissingPatException extends SecurityException {

    private final String system;

    public MissingPatException(String system, String headerPrefix) {
        super("Missing PAT header " + headerPrefix + capitalize(system));
        this.system = system;
    }

    private MissingPatException(String system, String message, boolean unused) {
        super(message);
        this.system = system;
    }

    /**
     * The OAuth-mode counterpart: the tool needs to act for the caller in a backend, and the caller
     * sent no access token to act with.
     */
    public static MissingPatException accessToken(String system) {
        return new MissingPatException(system,
                "Missing access token: send Authorization: Bearer <token> to use this tool.", true);
    }

    /** Convenience for the default {@code X-PAT-} prefix. */
    public MissingPatException(String system) {
        this(system, "X-PAT-");
    }

    /** Backend system key, lowercase (e.g. {@code "pve"}). */
    public String getSystem() {
        return system;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
