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

    private record Binding(String system, BackendCredential credential) {
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
            return List.of();
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
        CURRENT.set(new Binding(system, credential));
    }

    static void clear() {
        CURRENT.remove();
    }
}
