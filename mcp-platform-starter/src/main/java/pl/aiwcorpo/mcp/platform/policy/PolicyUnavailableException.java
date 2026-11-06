package pl.aiwcorpo.mcp.platform.policy;

/**
 * The server has no policy data it may decide with: nothing has been loaded from the gateway yet (the gateway is down, or this
 * server is not registered there yet). Fail-closed: a call that needs a decision is refused as {@code upstream_unavailable},
 * never allowed.
 */
public class PolicyUnavailableException extends RuntimeException {

    public PolicyUnavailableException(String message) {
        super(message, null, false, false);
    }
}
