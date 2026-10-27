package pl.aiwcorpo.mcp.platform.auth;

/**
 * What an internal JWT must be bound to: the kind of request it arrived with, the tool name of a
 * {@code tools/call} and the {@code X-Request-Id} header.
 *
 * @param kind            decides which variant (user or service) is accepted and whether {@code tool} must
 *                        be present
 * @param toolName        {@code params.name} of a {@code tools/call}, otherwise {@code null}
 * @param requestIdHeader the single {@code X-Request-Id} value, {@code null} when absent or repeated
 */
public record RequestBinding(Kind kind, String toolName, String requestIdHeader) {

    public enum Kind {
        /** {@code tools/call}: user variant only, {@code tool} required and equal to {@code params.name}. */
        TOOL_CALL,
        /**
         * The credential broker's {@code POST /v1/credentials/resolve}: user variant only, {@code tool}
         * required. Never produced by an MCP server; kept so the verifier covers every C2 rule.
         */
        TOOL_SCOPED,
        /**
         * {@code initialize}, {@code server/discover}, {@code tools/list}, {@code ping}, {@code GET /mcp-manifest} (and the broker's
         * {@code GET /v1/systems}): both variants, {@code tool} absent.
         */
        PROTOCOL,
        /** Everything else: user variant only, {@code tool} absent. */
        OTHER
    }

    public static RequestBinding toolCall(String toolName, String requestIdHeader) {
        return new RequestBinding(Kind.TOOL_CALL, toolName, requestIdHeader);
    }

    public static RequestBinding protocol(String requestIdHeader) {
        return new RequestBinding(Kind.PROTOCOL, null, requestIdHeader);
    }

    public static RequestBinding other(String requestIdHeader) {
        return new RequestBinding(Kind.OTHER, null, requestIdHeader);
    }

    public static RequestBinding toolScoped(String requestIdHeader) {
        return new RequestBinding(Kind.TOOL_SCOPED, null, requestIdHeader);
    }

    boolean serviceVariantAllowed() {
        return kind == Kind.PROTOCOL;
    }

    boolean toolRequired() {
        return kind == Kind.TOOL_CALL || kind == Kind.TOOL_SCOPED;
    }
}
