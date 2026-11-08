package pl.aiwcorpo.mcp.platform.auth;

/**
 * Whether this server has accepted policy data from the gateway, that is whether it was ever connected. The verifier uses it to stop
 * accepting the shared audience {@code mcp-pending} (CC-249): that audience is meant for the first manifest read only.
 */
public interface PolicyDataHeld {

    boolean held();
}
