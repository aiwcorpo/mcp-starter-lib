package pl.aiwcorpo.mcp.platform.security;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import org.springframework.http.HttpHeaders;

/**
 * The credential for the backend system, as the gateway injected it ({@code X-MCP-Credential:
 * <scheme> <value>}, {@code X-MCP-Credential-Type}, C3).
 *
 * <p>A class rather than a record so that nothing generated prints the value: {@link #toString()} shows
 * the scheme and type only. Hand {@link #value()} to the HTTP client and nowhere else.
 */
public final class BackendCredential {

    /** {@code scheme} of {@code X-MCP-Credential}. */
    public enum Scheme {
        BEARER("Bearer"),
        BASIC("Basic"),
        TOKEN("Token"),
        API_KEY("ApiKey");

        private final String wireName;

        Scheme(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        /** Exact, case-sensitive match of the C3 names. */
        public static Optional<Scheme> fromWireName(String name) {
            return Arrays.stream(values()).filter(s -> s.wireName.equals(name)).findFirst();
        }
    }

    /** {@code X-MCP-Credential-Type}. */
    public enum Type {
        USER_OAUTH("user-oauth"),
        USER_SECRET("user-secret"),
        TOKEN_EXCHANGE("token-exchange"),
        SERVICE_ACCOUNT("service-account");

        private final String wireName;

        Type(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static Optional<Type> fromWireName(String name) {
            return Arrays.stream(values()).filter(t -> t.wireName.equals(name)).findFirst();
        }
    }

    private final Scheme scheme;
    private final String value;
    private final Type type;

    public BackendCredential(Scheme scheme, String value, Type type) {
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.value = Objects.requireNonNull(value, "value");
        this.type = Objects.requireNonNull(type, "type");
        if (value.isEmpty()) {
            throw new IllegalArgumentException("credential value must not be empty");
        }
    }

    public Scheme scheme() {
        return scheme;
    }

    /** The secret. Pass it to the backend client; never log it, put it in an exception or return it. */
    public String value() {
        return value;
    }

    public Type type() {
        return type;
    }

    /** {@code <scheme> <value>}, the value of an {@code Authorization} header for the backend. */
    public String authorizationValue() {
        return scheme.wireName() + " " + value;
    }

    /**
     * Sets {@code Authorization: <scheme> <value>} on a backend request, e.g. in
     * {@code restClient.get().uri(...).headers(credential::applyTo)}. Systems that take the token in another
     * header (GitLab's {@code PRIVATE-TOKEN}) use {@link #value()} instead.
     */
    public void applyTo(HttpHeaders headers) {
        headers.set(HttpHeaders.AUTHORIZATION, authorizationValue());
    }

    @Override
    public String toString() {
        return "BackendCredential[scheme=" + scheme.wireName() + ", type=" + type.wireName() + ", value=<redacted>]";
    }
}
