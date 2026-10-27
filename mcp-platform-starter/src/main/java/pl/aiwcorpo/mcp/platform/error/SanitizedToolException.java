package pl.aiwcorpo.mcp.platform.error;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tool failure whose message is already safe to hand to an AI client.
 *
 * <p>Spring AI's MCP server catches {@link Exception} around every tool call and puts {@code e.getMessage()}
 * verbatim into the text content of the {@code CallToolResult} ({@code isError=true}). So the message of
 * this exception is the C10 error object itself, {@code {"error":"<code>","message":"<safe text>",...}},
 * and it is the only exception that leaves a tool ({@code AuditingToolCallback}).
 *
 * <p>Deliberately carries <b>no cause</b>: a cause would let detail leak back out through any handler that
 * prints it. The full detail is logged by {@link McpErrorMapper} under the request id.
 */
public class SanitizedToolException extends RuntimeException {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String code;
    private final String safeMessage;
    private final Map<String, Object> details;

    public SanitizedToolException(McpErrorMapper.SafeError error) {
        super(toJson(error), null, false, false);
        this.code = error.code();
        this.safeMessage = error.message();
        this.details = error.details();
    }

    /** C10 code, e.g. {@code invalid_argument}. */
    public String getCode() {
        return code;
    }

    /** The safe text alone, without the JSON around it. */
    public String getSafeMessage() {
        return safeMessage;
    }

    /** The code-specific fields of C10 ({@code system}, {@code connectUrl}, ...). */
    public Map<String, Object> getDetails() {
        return details;
    }

    public McpErrorMapper.SafeError toSafeError() {
        return new McpErrorMapper.SafeError(code, safeMessage, details);
    }

    private static String toJson(McpErrorMapper.SafeError error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error.code());
        body.put("message", error.message());
        body.putAll(error.details());
        return JSON.writeValueAsString(body);
    }
}
