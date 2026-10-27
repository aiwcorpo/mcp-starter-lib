package pl.aiwcorpo.mcp.platform.auth;

import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Decides what an incoming HTTP request is, for {@link RequestBinding}: a JSON-RPC {@code tools/call} (and
 * which tool), a protocol request the service variant may make, or anything else.
 *
 * <p>Every request with a body is classified from that body, whatever its path or content type, so a tool
 * call cannot be smuggled in through a path or transport this class does not know about. A body that is not
 * exactly one JSON object with unique keys is refused: JSON-RPC batches (arrays) would carry several calls
 * under one token, and an ambiguous object could be read differently by the MCP transport.
 */
final class McpRequestClassifier {

    /** JSON-RPC methods the gateway may send with its service variant (C2). */
    static final Set<String> PROTOCOL_METHODS = Set.of("initialize", "tools/list", "ping");

    /** C6 manifest endpoint; the service variant may read it. */
    static final String MANIFEST_PATH = "/mcp-manifest";

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
        if ("GET".equals(httpMethod) && MANIFEST_PATH.equals(path)) {
            return RequestBinding.protocol(requestIdHeader);
        }
        return RequestBinding.other(requestIdHeader);
    }
}
