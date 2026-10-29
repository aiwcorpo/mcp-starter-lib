package pl.aiwcorpo.mcp.platform.security;

import java.util.List;

/**
 * The check of the credential type a server makes before the policy, the grant probe and the resolvers (CC-166): a request that carries a
 * credential must name, in {@code X-MCP-Credential-Type}, one of the types the server accepts ({@code manifest.accepted-credential-types}).
 * A missing header, an unknown value (the comparison is exact, so {@code User-OAuth} is unknown) or a value outside the set is a refusal; so is a
 * server with no configured type. A request without a credential is not checked. Pure function, tested on the vectors of
 * {@code credential-type-header/}.
 */
public final class CredentialTypeCheck {

    private CredentialTypeCheck() {
    }

    /**
     * @param credentialSent whether the request carried {@code X-MCP-Credential}
     * @param typeHeader     the raw {@code X-MCP-Credential-Type}, {@code null} when absent
     * @param accepted       the types the server accepts
     * @return true when the call may go on
     */
    public static boolean admits(boolean credentialSent, String typeHeader, List<String> accepted) {
        if (!credentialSent) {
            return true;
        }
        return typeHeader != null && !accepted.isEmpty() && accepted.contains(typeHeader)
                && BackendCredential.Type.fromWireName(typeHeader).isPresent();
    }
}
