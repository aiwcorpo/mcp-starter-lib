package pl.aiwcorpo.mcp.platform.input;

/**
 * Whether a tool must publish a closed and bounded {@code inputSchema}.
 *
 * <p>The platform validates the arguments of every call against the tool's {@code inputSchema} (the MCP SDK's
 * own check is switched off, see {@code PlatformMcpServerCustomizer}). A tool without a schema, with a schema
 * that accepts undeclared properties, or with a level that has no limit (text without {@code maxLength}, a list
 * without {@code maxItems}, a number without bounds, a map, at any depth: {@link InputSchemaBounds}) would let
 * anything through that check, so outside the {@code dev} profile such a tool stops the server. In {@code dev}
 * it is logged and whatever the schema says is still enforced.
 *
 * <p>The policy comes from the active profiles only: a server cannot replace it with a bean (the starter refuses
 * to start outside {@code dev} when it finds one).
 *
 * @param requireBoundedSchema {@code true} = refuse to start for a tool without a schema, without
 *                             {@code additionalProperties: false}, or with an unbounded level
 */
public record ToolInputPolicy(boolean requireBoundedSchema) {

    public static final ToolInputPolicy STRICT = new ToolInputPolicy(true);
    public static final ToolInputPolicy DEV = new ToolInputPolicy(false);
}
