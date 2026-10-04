package pl.aiwcorpo.mcp.platform.autoconfigure;

/**
 * How callers authenticate — one choice for the whole server ({@code aiwcorpo.mcp.platform.auth-mode}).
 *
 * <p>The two modes are alternatives, not layers: a server accepts one kind of credential and uses
 * it both to decide what the caller may do here and to act for them in the backend.
 */
public enum AuthMode {

    /**
     * OAuth 2 / OIDC. The caller sends an access token ({@code Authorization: Bearer}) issued by the
     * identity provider. The server validates it, takes the caller's name and roles from it, and
     * forwards the same token to the backend. {@code X-PAT-*} headers are ignored.
     */
    OAUTH,

    /**
     * Personal access tokens. The caller sends their own backend token ({@code X-PAT-<system>}),
     * which the server forwards to that backend. No identity provider is involved: the server cannot
     * tell who the caller is, only that they hold a token, and every such caller gets the single
     * level the policy assigns to {@code pat}. Bearer tokens are rejected.
     */
    PAT
}
