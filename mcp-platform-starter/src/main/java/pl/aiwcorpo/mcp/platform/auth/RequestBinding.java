package pl.aiwcorpo.mcp.platform.auth;

/**
 * What an internal JWT must be bound to: the kind of request it arrived with, the tool name of a
 * {@code tools/call} and the {@code X-Request-Id} header.
 *
 * @param kind            decides which variant (user or service) is accepted, whether {@code tool} must be present and
 *                        which purpose ({@code use}) the token must have
 * @param toolName        {@code params.name} of a {@code tools/call}, otherwise {@code null}
 * @param requestIdHeader the single {@code X-Request-Id} value, {@code null} when absent or repeated
 */
public record RequestBinding(Kind kind, String toolName, String requestIdHeader) {

    /** Value of {@code use} for every request that is not the policy data endpoint (CC-249). */
    public static final String PURPOSE_CALL = "call";
    public static final String PURPOSE_POLICY_DATA = "policy-data";

    public enum Kind {
        /** {@code tools/call}: user variant only, {@code tool} required and equal to {@code params.name}. */
        TOOL_CALL,
        /**
         * The credential broker's {@code POST /v1/credentials/resolve}: user variant only, {@code tool}
         * required. Never produced by an MCP server; kept so the verifier covers every C2 rule.
         */
        TOOL_SCOPED,
        /**
         * {@code initialize}, {@code server/discover}, {@code tools/list}, {@code ping} (and the broker's {@code GET /v1/systems}):
         * both variants, {@code tool} absent, purpose {@code call}.
         */
        PROTOCOL,
        /** {@code POST /.well-known/mcp-policy-data}: service variant only, purpose {@code policy-data}. */
        POLICY_DATA,
        /** Everything else: user variant only, {@code tool} absent. */
        OTHER
    }

    public static RequestBinding toolCall(String toolName, String requestIdHeader) {
        return new RequestBinding(Kind.TOOL_CALL, toolName, requestIdHeader);
    }

    public static RequestBinding protocol(String requestIdHeader) {
        return new RequestBinding(Kind.PROTOCOL, null, requestIdHeader);
    }

    public static RequestBinding policyData(String requestIdHeader) {
        return new RequestBinding(Kind.POLICY_DATA, null, requestIdHeader);
    }

    public static RequestBinding other(String requestIdHeader) {
        return new RequestBinding(Kind.OTHER, null, requestIdHeader);
    }

    public static RequestBinding toolScoped(String requestIdHeader) {
        return new RequestBinding(Kind.TOOL_SCOPED, null, requestIdHeader);
    }

    /** The value {@code use} must have: a token minted for one purpose is never accepted for another. */
    String purpose() {
        return switch (kind) {
            case POLICY_DATA -> PURPOSE_POLICY_DATA;
            default -> PURPOSE_CALL;
        };
    }

    boolean serviceVariantAllowed() {
        return kind == Kind.PROTOCOL || kind == Kind.POLICY_DATA;
    }

    boolean toolRequired() {
        return kind == Kind.TOOL_CALL || kind == Kind.TOOL_SCOPED;
    }
}
