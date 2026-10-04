package pl.aiwcorpo.mcp.platform.guard;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * The resolver of a server with no identity provider configured: nobody can be identified, so every
 * caller is anonymous (read-only by default). A bearer token cannot be checked here, and an
 * unchecked token must not be mistaken for "no token" — it is rejected.
 */
public class NoIdentityProviderResolver implements IdentityResolver {

    @Override
    public Optional<CallerIdentity> resolve(HttpServletRequest request) {
        if (OidcIdentityResolver.bearerToken(request).isPresent()) {
            throw new InvalidCredentialsException(
                    "This server has no identity provider configured and cannot accept access tokens.");
        }
        return Optional.empty();
    }
}
