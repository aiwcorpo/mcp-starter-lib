package pl.aiwcorpo.mcp.platform.security;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;

/**
 * Removes the current request's credential from text that is about to be logged or returned. Used by the
 * error mapper on exception messages, which is where secrets end up in practice: a backend echoing the
 * {@code Authorization} header in its error body, or a client library quoting the request.
 */
public final class SecretRedactor {

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
            if (secret.length() < MINIMUM_LENGTH) {
                continue;
            }
            for (String form : forms(secret)) {
                result = result.replace(form, REDACTED);
            }
        }
        return result;
    }

    /** The secret as it appears raw, URL-encoded (both cases of the hex digits) and inside a JSON string. */
    private static Set<String> forms(String secret) {
        Set<String> forms = new LinkedHashSet<>();
        forms.add(secret);
        String urlEncoded = URLEncoder.encode(secret, StandardCharsets.UTF_8);
        forms.add(urlEncoded);
        forms.add(urlEncoded.replace("+", "%20"));
        forms.add(lowerHex(urlEncoded));
        String json = JSON.writeValueAsString(secret);
        forms.add(json.substring(1, json.length() - 1));
        forms.add(secret.replace("/", "\\/"));
        return forms;
    }

    private static String lowerHex(String urlEncoded) {
        StringBuilder out = new StringBuilder(urlEncoded.length());
        for (int i = 0; i < urlEncoded.length(); i++) {
            char c = urlEncoded.charAt(i);
            out.append(c);
            if (c == '%' && i + 2 < urlEncoded.length()) {
                out.append(Character.toLowerCase(urlEncoded.charAt(i + 1)));
                out.append(Character.toLowerCase(urlEncoded.charAt(i + 2)));
                i += 2;
            }
        }
        return out.toString();
    }
}
