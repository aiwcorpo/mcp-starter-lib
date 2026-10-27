package pl.aiwcorpo.mcp.platform.input;

/**
 * Whether a tool must publish a closed {@code inputSchema}.
 *
 * <p>The platform validates the arguments of every call against the tool's {@code inputSchema} (the MCP SDK's
 * own check is switched off, see {@code PlatformMcpServerCustomizer}). A tool without a schema, or with a schema
 * that accepts undeclared properties, would let anything through that check, so outside the {@code dev} profile
 * such a tool stops the server. In {@code dev} it is logged and whatever the schema says is still enforced.
 *
 * @param requireClosedSchema {@code true} = refuse to start for a tool without a schema or without
 *                            {@code additionalProperties: false}
 */
public record ToolInputPolicy(boolean requireClosedSchema) {

    public static final ToolInputPolicy STRICT = new ToolInputPolicy(true);
    public static final ToolInputPolicy DEV = new ToolInputPolicy(false);
}
