package pl.aiwcorpo.mcp.platform.auth;

/**
 * What kind of receiver of the internal JWT this process is (CC-226, {@code verifier.receiverMode} of the contract vectors). The
 * schema cannot tell the receiver, so the receiver says it.
 */
public enum ReceiverMode {

    /** A server written on the starter: a token with {@code mcp_risk} or {@code mcp_action} is {@code invalid_claims}. */
    NATIVE,

    /**
     * The sidecar of an adapter in front of a foreign server: takes the administrator's classification from the token. A
     * {@code tools/call} without {@code mcp_risk} and {@code mcp_action} is refused ({@code classification_missing}), a pair
     * that does not fit the schema is {@code invalid_claims}, a risk other than {@code READ_ONLY} is logged and counted.
     */
    ADAPTER
}
