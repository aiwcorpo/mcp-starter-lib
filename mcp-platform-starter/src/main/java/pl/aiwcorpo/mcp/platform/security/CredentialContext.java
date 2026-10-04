package pl.aiwcorpo.mcp.platform.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Request-scoped holder for the credential the server forwards to backends on the caller's behalf:
 * per-system PATs (PAT mode) or the caller's access token (OAuth mode).
 *
 * <p>Filled by {@link PatForwardingFilter} from inbound headers, read by outbound adapters, and
 * cleared at the end of the request. The values are secrets — never log them.
 */
public final class CredentialContext {

    private static final ThreadLocal<Map<String, String>> PATS = new ThreadLocal<>();
    private static final ThreadLocal<String> ACCESS_TOKEN = new ThreadLocal<>();

    private CredentialContext() {
    }

    static void set(Map<String, String> pats) {
        PATS.set(pats);
    }

    static void clear() {
        PATS.remove();
    }

    /**
     * Platform use only (OAuth mode): the caller's validated access token for this request. Must be
     * paired with {@link #clearAccessToken()} in a {@code finally}.
     */
    public static void setAccessToken(String token) {
        ACCESS_TOKEN.set(token);
    }

    public static void clearAccessToken() {
        ACCESS_TOKEN.remove();
    }

    /**
     * The token an outbound adapter should present to backend {@code system} on behalf of the caller:
     * their PAT for that system in PAT mode, their access token in OAuth mode. This is the one call
     * an adapter needs; it does not have to know which mode the server runs in.
     *
     * @throws MissingPatException naming what the caller has to send, if they sent no usable credential
     */
    public static String requireBackendToken(String system) {
        Optional<String> pat = pat(system);
        if (pat.isPresent()) {
            return pat.get();
        }
        String accessToken = ACCESS_TOKEN.get();
        if (accessToken != null) {
            return accessToken;
        }
        // PATS is set (possibly empty) only when the PAT filter ran, i.e. in PAT mode.
        throw PATS.get() != null ? new MissingPatException(system) : MissingPatException.accessToken(system);
    }

    /** PAT the caller supplied for a backend system (e.g. {@code "jira"}) on this request, if any. */
    public static Optional<String> pat(String system) {
        Map<String, String> pats = PATS.get();
        return pats == null ? Optional.empty() : Optional.ofNullable(pats.get(system.toLowerCase()));
    }

    /**
     * Backend systems the caller supplied a PAT for on this request — <b>keys only, never values</b>.
     * Safe to record in the audit log; it says which backend was reachable, not what the secret was.
     */
    public static Set<String> systems() {
        Map<String, String> pats = PATS.get();
        return pats == null ? Set.of() : Set.copyOf(pats.keySet());
    }

    /**
     * A short, stable, non-reversible tag for the set of PATs on this request, or empty if there are
     * none. Lets the audit log tell one token holder from another without ever holding the token.
     */
    public static Optional<String> patFingerprint() {
        Map<String, String> pats = PATS.get();
        if (pats == null || pats.isEmpty()) {
            return Optional.empty();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            new TreeMap<>(pats).forEach((system, value) -> {
                digest.update(system.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            });
            return Optional.of(HexFormat.of().formatHex(digest.digest(), 0, 6));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
