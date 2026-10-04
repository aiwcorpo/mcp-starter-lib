package pl.aiwcorpo.mcp.platform.guard;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Turns an inbound request into a {@link CallerIdentity}. This is the plug point for the
 * organisation's IAM. The platform ships {@link OidcIdentityResolver} (JWT access tokens from any
 * OIDC provider), active once {@code guard.oidc} is configured; a server with other needs (opaque
 * tokens, mTLS) registers its own bean.
 */
public interface IdentityResolver {

    /**
     * @return the caller, or empty when the request carries no credentials at all
     * @throws InvalidCredentialsException when credentials were presented but are not valid — never
     *         downgrade those to anonymous, or a typo in a token silently becomes read-only access
     */
    Optional<CallerIdentity> resolve(HttpServletRequest request);

    /** Credentials were presented and rejected. Maps to HTTP 401. */
    class InvalidCredentialsException extends RuntimeException {

        public InvalidCredentialsException(String message) {
            super(message);
        }
    }
}
