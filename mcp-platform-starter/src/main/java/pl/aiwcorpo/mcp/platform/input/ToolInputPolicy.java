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
 * <p>An enum, so a server cannot build a third value; the policy comes from the active profiles only, and the
 * wrapper that uses it ({@code AuditingToolCallback}) cannot be constructed outside the starter.
 */
public enum ToolInputPolicy {

    /** A tool without a closed and bounded {@code inputSchema} stops the server. */
    STRICT(true),

    /** Only the one-and-only {@code dev} profile: such a tool is logged and still validated as far as it declares. */
    DEV(false);

    private final boolean requireBoundedSchema;

    ToolInputPolicy(boolean requireBoundedSchema) {
        this.requireBoundedSchema = requireBoundedSchema;
    }

    /** {@code true} = refuse to start for a tool without a schema, without {@code additionalProperties: false}, or with an unbounded level. */
    public boolean requireBoundedSchema() {
        return requireBoundedSchema;
    }
}
