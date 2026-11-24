package pl.aiwcorpo.mcp.platform.auth;

/**
 * What an internal JWT must be bound to: the kind of request it arrived with, the tool name of a
 * {@code tools/call}, the URI of a {@code resources/read} and the {@code X-Request-Id} header.
 *
 * @param kind            decides which variant (user or service) is accepted, whether {@code tool} or {@code resource} must be
 *                        present and which purpose ({@code use}) the token must have
 * @param toolName        {@code params.name} of a {@code tools/call}, otherwise {@code null}
 * @param requestIdHeader the single {@code X-Request-Id} value, {@code null} when absent or repeated
 * @param resourceUri     {@code params.uri} of a {@code resources/read}, otherwise {@code null}; a {@code resources/read} without a
 *                        string there is bound to {@code null}, which no token can equal
 * @param unsupportedFamily for {@link Kind#UNSUPPORTED}: the method family ({@code prompts/*}) that is refused after the token is
 *                        verified, otherwise {@code null}
 */
public record RequestBinding(Kind kind, String toolName, String requestIdHeader, String resourceUri, String unsupportedFamily) {

    /** A binding without a resource URI and without a refused method family: every kind the contract knew before 8.0.0. */
    public RequestBinding(Kind kind, String toolName, String requestIdHeader) {
        this(kind, toolName, requestIdHeader, null, null);
    }

    /** Value of {@code use} for every request that is not the policy data endpoint (CC-249). */
    public static final String PURPOSE_CALL = "call";
    public static final String PURPOSE_POLICY_DATA = "policy-data";

    public enum Kind {
        /** {@code tools/call}: user variant only, {@code tool} required and equal to {@code params.name}. */
        TOOL_CALL,
        /**
         * The credential broker's {@code POST /v1/credentials/resolve} and its authenticator calls: user variant only, exactly one of
         * {@code tool} and {@code resource} (CC-260). Never produced by an MCP server; kept so the verifier covers every C2 rule.
         */
        TOOL_SCOPED,
        /**
         * {@code initialize}, {@code server/discover}, {@code tools/list}, {@code ping} (and the broker's {@code GET /v1/systems}):
         * both variants, {@code tool} absent, purpose {@code call}.
         */
        PROTOCOL,
        /** {@code POST /.well-known/mcp-policy-data}: service variant only, purpose {@code policy-data}. */
        POLICY_DATA,
        /** {@code resources/read} (CC-260): user variant only, {@code resource} required and equal to {@code params.uri}, {@code tool} absent. */
        RESOURCE_READ,
        /** {@code resources/list}, {@code resources/templates/list} (CC-260): both variants, neither {@code tool} nor {@code resource}. */
        RESOURCE_LIST,
        /**
         * A method of a family this version of the starter does not serve ({@code prompts/*}, {@code completion/*}, {@code logging/*},
         * {@code resources/*} other than the three above): verified like {@link #OTHER}, then refused by {@link InternalJwtFilter}.
         */
        UNSUPPORTED,
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

    public static RequestBinding resourceRead(String uri, String requestIdHeader) {
        return new RequestBinding(Kind.RESOURCE_READ, null, requestIdHeader, uri, null);
    }

    public static RequestBinding resourceList(String requestIdHeader) {
        return new RequestBinding(Kind.RESOURCE_LIST, null, requestIdHeader, null, null);
    }

    public static RequestBinding unsupported(String family, String requestIdHeader) {
        return new RequestBinding(Kind.UNSUPPORTED, null, requestIdHeader, null, family);
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
        return kind == Kind.PROTOCOL || kind == Kind.POLICY_DATA || kind == Kind.RESOURCE_LIST;
    }

    boolean toolRequired() {
        return kind == Kind.TOOL_CALL;
    }

    /** True for the requests the starter answers with a refusal once the token is verified, because it serves no resources or prompts yet. */
    boolean refusedUntilSupported() {
        return kind == Kind.RESOURCE_READ || kind == Kind.RESOURCE_LIST || kind == Kind.UNSUPPORTED;
    }

    /** What the log says about a refused request: the method, or its family when the method itself is the caller's text. */
    String refusedMethod() {
        return switch (kind) {
            case RESOURCE_READ -> "resources/read";
            case RESOURCE_LIST -> "resources/*list";
            default -> unsupportedFamily;
        };
    }
}
