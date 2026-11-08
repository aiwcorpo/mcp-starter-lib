package pl.aiwcorpo.mcp.platform.auth;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Decides what an incoming HTTP request is, for {@link RequestBinding}: the policy data endpoint of the gateway,
 * a JSON-RPC {@code tools/call} (and which tool), a protocol request the service variant may
 * make, or anything else.
 *
 * <p>Every request with a body is classified from that body, whatever its path or content type, so a tool
 * call cannot be smuggled in through a path or transport this class does not know about. A body that is not
 * exactly one JSON object with unique keys is refused: JSON-RPC batches (arrays) would carry several calls
 * under one token, and an ambiguous object could be read differently by the MCP transport.
 */
final class McpRequestClassifier {

    /** JSON-RPC methods the gateway may send with its service variant (C2). */
    static final Set<String> PROTOCOL_METHODS = Set.of("initialize", "server/discover", "tools/list", "ping");

    /** C14: the manifest the gateway pulls; served without a token, so {@link InternalJwtFilter} never lets it reach this class. */
    static final String MANIFEST_PATH = "/.well-known/mcp-manifest";

    /** C14: the policy data the gateway pushes, with a token of purpose {@code policy-data}. */
    static final String POLICY_DATA_PATH = "/.well-known/mcp-policy-data";

    static final class InvalidRequestException extends Exception {
        InvalidRequestException() {
            super("request body is not a single JSON object", null, false, false);
        }
    }

    private McpRequestClassifier() {
    }

    /**
     * @param httpMethod   HTTP method
     * @param path         request path without the context path
     * @param body         buffered body, empty when there is none
     * @param requestIdHeader single {@code X-Request-Id} value or {@code null}
     */
    static RequestBinding classify(String httpMethod, String path, byte[] body, String requestIdHeader)
            throws InvalidRequestException {
        // The policy data endpoint first: its body is a JSON object with no JSON-RPC method, and it must
        // not be mistaken for a message of the MCP endpoint (nor the other way round).
        if ("POST".equals(httpMethod) && POLICY_DATA_PATH.equals(path)) {
            return RequestBinding.policyData(requestIdHeader);
        }
        if (body.length > 0) {
            JsonNode message = StrictJson.parse(body);
            if (message == null || !message.isObject()) {
                throw new InvalidRequestException();
            }
            JsonNode method = message.get("method");
            if (method == null || !method.isString()) {
                return RequestBinding.other(requestIdHeader);
            }
            String name = method.asString();
            if ("tools/call".equals(name)) {
                JsonNode params = message.get("params");
                JsonNode tool = params == null ? null : params.get("name");
                return RequestBinding.toolCall(tool != null && tool.isString() ? tool.asString() : null,
                        requestIdHeader);
            }
            return PROTOCOL_METHODS.contains(name)
                    ? RequestBinding.protocol(requestIdHeader)
                    : RequestBinding.other(requestIdHeader);
        }
        return RequestBinding.other(requestIdHeader);
    }
}
