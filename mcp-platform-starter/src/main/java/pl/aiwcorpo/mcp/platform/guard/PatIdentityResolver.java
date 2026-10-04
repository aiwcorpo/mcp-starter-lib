package pl.aiwcorpo.mcp.platform.guard;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;

/**
 * Identity in PAT mode. There is no identity provider, so the honest answer to "Who am I?" is
 * "someone holding a backend token": the caller is named after a fingerprint of that token and gets
 * the one level the policy assigns to {@code pat}.
 *
 * <p><b>What this does and does not prove.</b> The server cannot check a PAT — only the backend can.
 * A made-up value in {@code X-PAT-*} therefore earns the {@code pat} level here, and is stopped only
 * when the backend rejects it. In this mode the guard decides what a token holder may ATTEMPT; the
 * backend remains the one that decides what they may DO. Use OAuth mode when this server itself has
 * to know who is calling.
 *
 * <p>A bearer token cannot be validated in this mode and is rejected rather than ignored.
 */
public class PatIdentityResolver implements IdentityResolver {

    private final GuardPolicyStore policies;

    public PatIdentityResolver(GuardPolicyStore policies) {
        this.policies = policies;
    }

    @Override
    public Optional<CallerIdentity> resolve(HttpServletRequest request) {
        if (OidcIdentityResolver.bearerToken(request).isPresent()) {
            throw new InvalidCredentialsException(
                    "This server authenticates with personal access tokens (X-PAT-<system>), not bearer tokens.");
        }
        // Filled by PatForwardingFilter, which runs before the identity filter.
        return CredentialContext.patFingerprint().map(fingerprint ->
                new CallerIdentity("pat:" + fingerprint, false, policies.current().patMaxRisk(), null, null));
    }
}
