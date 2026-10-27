package pl.aiwcorpo.mcp.platform.text;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks the JSON a tool returned against the {@code outputSchema} the platform published for it
 * ({@link ToolOutputSchemas}), before the result leaves the tool wrapper.
 *
 * <p>The MCP SDK validates {@code structuredContent} against the schema too, but after the audit event has been
 * written and with a message of its own, so a result with a {@code null} in a component not marked
 * {@code @Nullable} used to be audited as {@code ok} and reach the client as an {@code isError} text that is not
 * C10. Checking here turns it into an {@code internal_error} (C10) and an audit event with
 * {@code outcome=error}. The message names where the result is wrong (instance path and rule), never a value:
 * the values are the system's text.
 */
public final class ToolResultValidator {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final int MAX_REPORTED = 5;

    private final String tool;
    private final Schema schema;

    private ToolResultValidator(String tool, Schema schema) {
        this.tool = tool;
        this.schema = schema;
    }

    public static ToolResultValidator compile(String tool, ObjectNode outputSchema) {
        try {
            Schema schema = REGISTRY.getSchema(outputSchema);
            schema.initializeValidators();
            return new ToolResultValidator(tool, schema);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Tool '" + tool + "': outputSchema cannot be compiled ("
                    + ex.getClass().getSimpleName() + ")", ex);
        }
    }

    /** @throws IllegalStateException when the result is not JSON or does not satisfy the schema */
    public void validate(String result) {
        JsonNode node;
        try {
            node = JSON.readTree(result == null ? "" : result);
        } catch (JacksonException ex) {
            throw new IllegalStateException("The result of tool '" + tool + "' is not JSON, but the tool publishes an "
                    + "outputSchema");
        }
        List<Error> errors = schema.validate(node);
        if (errors.isEmpty()) {
            return;
        }
        List<String> problems = new ArrayList<>();
        for (Error error : errors) {
            String where = error.getInstanceLocation() == null ? "" : error.getInstanceLocation().toString();
            String problem = (where.isEmpty() ? "/" : where) + " (" + error.getKeyword() + ")";
            if (!problems.contains(problem)) {
                problems.add(problem);
            }
            if (problems.size() == MAX_REPORTED) {
                break;
            }
        }
        throw new IllegalStateException("The result of tool '" + tool + "' does not match its outputSchema at "
                + String.join(", ", problems) + ". A result component that can be null needs @Nullable "
                + "(see MIGRATION-3.0.md, alpha-8).");
    }
}
