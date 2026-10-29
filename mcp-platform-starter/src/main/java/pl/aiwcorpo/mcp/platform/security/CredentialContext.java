package pl.aiwcorpo.mcp.platform.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * The backend credential of the current request, for outbound adapters.
 *
 * <pre>{@code
 * BackendCredential credential = CredentialContext.require("jira");
 * restClient.get().uri("/rest/api/2/issue/{key}", key).headers(credential::applyTo).retrieve()...
 * }</pre>
 *
 * Filled by {@link CredentialHeaderFilter} from the gateway's headers (C3) and cleared when the request
 * ends. A server serves one system, so the credential belongs to that system; asking for another one is a
 * programming error.
 */
public final class CredentialContext {

    /**
     * @param credential     the credential, absent when none was sent or when its type header was missing or unknown
     * @param credentialSent whether the request carried {@code X-MCP-Credential} (or a dev-pat token)
     * @param typeHeader     the raw {@code X-MCP-Credential-Type}, {@code null} when absent
     * @param unusableValue  the value of a credential that was sent without a usable type, kept only so that it is redacted
     */
    private record Binding(String system, BackendCredential credential, boolean credentialSent, String typeHeader,
                           String unusableValue) {
    }

    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();

    private CredentialContext() {
    }

    /**
     * The credential for {@code system}.
     *
     * @throws CredentialRequiredException when the request carries no credential ({@code credential_required})
     * @throws IllegalStateException       when {@code system} is not the system of this server
     */
    public static BackendCredential require(String system) {
        Binding binding = CURRENT.get();
        if (binding != null && !binding.system().equals(system)) {
            throw new IllegalStateException("this server serves system '" + binding.system()
                    + "'; there is no credential for '" + system + "'");
        }
        if (binding == null || binding.credential() == null) {
            throw new CredentialRequiredException(system);
        }
        return binding.credential();
    }

    /** The credential of this request, if the gateway sent one. */
    public static Optional<BackendCredential> current() {
        Binding binding = CURRENT.get();
        return binding == null ? Optional.empty() : Optional.ofNullable(binding.credential());
    }

    /** True when the request carried a credential, whether or not its type header was usable (CC-166). */
    public static boolean credentialSent() {
        Binding binding = CURRENT.get();
        return binding != null && binding.credentialSent();
    }

    /** The raw {@code X-MCP-Credential-Type} of this request, empty when there was none; for the check against the server's types. */
    public static Optional<String> typeHeader() {
        Binding binding = CURRENT.get();
        return binding == null ? Optional.empty() : Optional.ofNullable(binding.typeHeader());
    }

    /** {@code X-MCP-Credential-Type} of this request, for the audit; never the value. */
    public static Optional<BackendCredential.Type> type() {
        return current().map(BackendCredential::type);
    }

    /**
     * Strings that must not appear in logs or tool responses: the value and, for {@code Basic}, the decoded
     * {@code user:password} and the password.
     */
    static List<String> secrets() {
        Optional<BackendCredential> credential = current();
        if (credential.isEmpty()) {
            Binding binding = CURRENT.get();
            return binding == null || binding.unusableValue() == null ? List.of() : List.of(binding.unusableValue());
        }
        List<String> secrets = new ArrayList<>();
        String value = credential.get().value();
        secrets.add(value);
        if (credential.get().scheme() == BackendCredential.Scheme.BASIC) {
            try {
                String pair = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
                secrets.add(pair);
                int colon = pair.indexOf(':');
                if (colon >= 0 && pair.length() - colon - 1 >= 4) {
                    secrets.add(pair.substring(colon + 1));
                }
            } catch (IllegalArgumentException notBase64) {
                // Nothing more to hide: the backend will reject it, and the raw value is already listed.
            }
        }
        return secrets;
    }

    static void bind(String system, BackendCredential credential) {
        CURRENT.set(new Binding(system, credential, credential != null,
                credential == null ? null : credential.type().wireName(), null));
    }

    /** A credential was sent but its type header is missing or unknown: no usable credential, the call is refused by the type check. */
    static void bindUntyped(String system, String typeHeader, String value) {
        CURRENT.set(new Binding(system, null, true, typeHeader, value));
    }

    static void clear() {
        CURRENT.remove();
    }
}
