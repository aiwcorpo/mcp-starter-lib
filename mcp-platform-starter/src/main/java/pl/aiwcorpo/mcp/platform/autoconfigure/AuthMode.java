package pl.aiwcorpo.mcp.platform.autoconfigure;

/**
 * Where the backend credential of a call comes from ({@code aiwcorpo.mcp.platform.auth-mode}). In both
 * modes the caller is identified by the gateway's internal JWT (C2); the mode only changes the
 * credential source.
 */
public enum AuthMode {

    /** {@code X-MCP-Credential} and {@code X-MCP-Credential-Type} set by the gateway (C3). Default. */
    GATEWAY,

    /**
     * Local development only: additionally accepts the developer's own token in {@code X-PAT-<system>}.
     * The server refuses to start in this mode unless the {@code dev} profile is active.
     */
    DEV_PAT
}
