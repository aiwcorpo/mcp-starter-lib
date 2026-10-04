package pl.aiwcorpo.mcp.platform.guard;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * "Who am I?" answered by the organisation's identity provider: the caller presents an OAuth 2
 * access token ({@code Authorization: Bearer <JWT>}) issued by an OIDC provider, and this server —
 * an OAuth 2 <em>resource server</em> — validates it and reads the caller from its claims.
 *
 * <p>Validation (signature against the provider's published keys, issuer, expiry, audience) is done
 * by the {@link JwtDecoder}. What is specific to this platform is the last step: the roles, groups
 * or scopes in the token are looked up in the guard policy, which says what each is worth here.
 * The provider says who you are and which groups you are in; the policy file, owned by whoever runs
 * this server, says what that lets you do.
 *
 * <p>Works with any provider that issues JWT access tokens. Where the roles live differs between
 * them, hence the configurable claim list:
 * <ul>
 *   <li>Keycloak — {@code realm_access.roles}, {@code resource_access.<client>.roles}</li>
 *   <li>Microsoft Entra ID — {@code roles} (app roles), {@code groups}, {@code scp}</li>
 *   <li>Okta, Auth0 — {@code groups}, {@code permissions}, {@code scope}</li>
 * </ul>
 *
 * <p>The server never sees a password and never runs a login flow; obtaining the token is between
 * the MCP client and the provider.
 */
public class OidcIdentityResolver implements IdentityResolver {

    private static final Logger LOG = LoggerFactory.getLogger(OidcIdentityResolver.class);

    private static final String BEARER = "bearer ";

    private final JwtDecoder decoder;
    private final GuardPolicyStore policies;
    private final String subjectClaim;
    private final List<String> roleClaims;

    /**
     * @param subjectClaim claim used as the caller's name in the audit log, e.g. {@code sub} or
     *                     {@code preferred_username}; falls back to {@code sub} when absent
     * @param roleClaims   claims to collect roles from; a dot walks into nested objects
     */
    public OidcIdentityResolver(JwtDecoder decoder, GuardPolicyStore policies, String subjectClaim,
                                List<String> roleClaims) {
        this.decoder = decoder;
        this.policies = policies;
        this.subjectClaim = subjectClaim;
        this.roleClaims = List.copyOf(roleClaims);
    }

    @Override
    public Optional<CallerIdentity> resolve(HttpServletRequest request) {
        Optional<String> token = bearerToken(request);
        if (token.isEmpty()) {
            return Optional.empty();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(token.get());
        } catch (JwtException ex) {
            // The reason (expired, wrong audience, bad signature…) is for our log, not for the caller.
            LOG.warn("event=token_rejected reason=\"{}\"", ex.getMessage());
            throw new InvalidCredentialsException("The access token is not valid for this server.");
        } catch (RuntimeException ex) {
            // Typically: the provider's keys could not be fetched. Fail closed.
            LOG.error("event=token_validation_unavailable reason=\"{}\"", ex.toString());
            throw new InvalidCredentialsException("The access token could not be validated.");
        }
        String subject = text(jwt.getClaims().get(subjectClaim));
        if (subject == null) {
            subject = jwt.getSubject();
        }
        if (subject == null || subject.isBlank()) {
            throw new InvalidCredentialsException("The access token names no subject.");
        }
        return Optional.of(policies.current().identityFor(subject, rolesOf(jwt.getClaims())));
    }

    /** The bearer token, or empty when there is none (another scheme is not a credential for us). */
    static Optional<String> bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || header.length() <= BEARER.length()
                || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return Optional.empty();
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    Set<String> rolesOf(Map<String, Object> claims) {
        Set<String> roles = new LinkedHashSet<>();
        for (String path : roleClaims) {
            Object value = claims;
            for (String step : path.split("\\.")) {
                value = value instanceof Map<?, ?> map ? map.get(step) : null;
            }
            if (value instanceof Collection<?> items) {
                items.forEach(item -> roles.add(String.valueOf(item)));
            } else if (value instanceof String text) {
                // OAuth "scope" is one space-delimited string
                for (String part : text.trim().split("\\s+")) {
                    if (!part.isEmpty()) {
                        roles.add(part);
                    }
                }
            }
        }
        return roles;
    }

    private static String text(Object claim) {
        return claim instanceof String text && !text.isBlank() ? text : null;
    }
}
