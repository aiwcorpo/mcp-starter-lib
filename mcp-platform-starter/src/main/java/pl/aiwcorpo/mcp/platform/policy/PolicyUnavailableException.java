package pl.aiwcorpo.mcp.platform.policy;

/**
 * The server has no policy it may decide with: no bundle yet, or an emergency (overrides) bundle that was active before
 * a restart and has not been loaded again. Fail-closed: a call that needs a decision is refused as
 * {@code upstream_unavailable}, never allowed.
 */
public class PolicyUnavailableException extends RuntimeException {

    public PolicyUnavailableException(String message) {
        super(message, null, false, false);
    }
}
