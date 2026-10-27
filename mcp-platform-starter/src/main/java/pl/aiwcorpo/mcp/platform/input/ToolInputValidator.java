package pl.aiwcorpo.mcp.platform.input;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.definition.ToolDefinition;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the arguments of one tool call against that tool's {@code inputSchema} (JSON Schema 2020-12: types,
 * {@code required}, {@code additionalProperties}, {@code pattern}, {@code minLength}/{@code maxLength},
 * {@code minimum}/{@code maximum}, {@code enum}, ...).
 *
 * <p>Compiled once, when the tool is registered, so a broken or open schema stops the server at startup
 * ({@link ToolInputPolicy}) instead of failing a request.
 *
 * <h2>What the error says</h2>
 * A violation becomes an {@link InvalidToolArgumentException} ({@code invalid_argument}, C10) that names the
 * argument and the rule, written from the schema: never the value the client sent, and never a property name
 * the schema does not declare (an unknown name is client input too, so it is reported only as "unknown
 * arguments are not allowed"). The library's own messages are not used because some of them quote the
 * instance.
 */
public final class ToolInputValidator {

    private static final Logger LOG = LoggerFactory.getLogger(ToolInputValidator.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final SchemaRegistry REGISTRY = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    private static final int MAX_REPORTED = 5;
    private static final ToolInputValidator NONE = new ToolInputValidator(null, Set.of(), null);

    private final Schema schema;
    private final Set<String> declared;
    private final JsonNode properties;

    private ToolInputValidator(Schema schema, Set<String> declared, JsonNode properties) {
        this.schema = schema;
        this.declared = declared;
        this.properties = properties;
    }

    /**
     * @throws IllegalStateException when the schema cannot be used, or (with {@link ToolInputPolicy#requireClosedSchema})
     *                               is missing or not closed
     */
    public static ToolInputValidator compile(ToolDefinition tool, ToolInputPolicy policy) {
        String name = tool.name();
        String text = tool.inputSchema();
        if (text == null || text.isBlank()) {
            return open(name, "has no inputSchema", policy);
        }
        JsonNode root;
        try {
            root = JSON.readTree(text);
        } catch (JacksonException ex) {
            throw new IllegalStateException("Tool '" + name + "': inputSchema is not valid JSON", ex);
        }
        if (!root.isObject()) {
            throw new IllegalStateException("Tool '" + name + "': inputSchema must be a JSON object");
        }
        boolean closed = "object".equals(root.path("type").asString(null))
                && root.path("additionalProperties").isBoolean() && !root.path("additionalProperties").asBoolean(true);
        if (closed) {
            return compiled(name, root);
        }
        // Outside dev open() throws; in dev the schema is still enforced as far as it goes.
        open(name, "has an inputSchema without type: object and additionalProperties: false", policy);
        return compiled(name, root);
    }

    private static ToolInputValidator open(String tool, String what, ToolInputPolicy policy) {
        String message = "Tool '" + tool + "' " + what + ": arguments the schema does not declare would reach the "
                + "tool. Declare the arguments (@ToolParam) and publish additionalProperties: false "
                + "(SchemaLimitedToolCallbacks.of(...) adds it).";
        if (policy.requireClosedSchema()) {
            throw new IllegalStateException(message);
        }
        LOG.warn("event=tool_schema_open tool=\"{}\" detail=\"{}\" profile=dev", tool, message);
        return NONE;
    }

    private static ToolInputValidator compiled(String name, JsonNode root) {
        Schema schema;
        try {
            schema = REGISTRY.getSchema(root);
            schema.initializeValidators();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Tool '" + name + "': inputSchema cannot be compiled ("
                    + ex.getClass().getSimpleName() + ")", ex);
        }
        Set<String> declared = new LinkedHashSet<>(root.path("properties").propertyNames());
        return new ToolInputValidator(schema, declared, root.path("properties"));
    }

    /**
     * @param toolInput the arguments object as JSON text, {@code null} or blank for none
     * @throws InvalidToolArgumentException when the arguments do not satisfy the schema
     */
    public void validate(String toolInput) {
        if (schema == null) {
            return;
        }
        JsonNode arguments;
        try {
            arguments = toolInput == null || toolInput.isBlank() ? JSON.createObjectNode() : JSON.readTree(toolInput);
        } catch (JacksonException ex) {
            throw new InvalidToolArgumentException("The arguments are not a valid JSON object.");
        }
        List<Error> errors = schema.validate(arguments);
        if (errors.isEmpty()) {
            return;
        }
        List<String> problems = new ArrayList<>();
        for (Error error : errors) {
            String problem = describe(error);
            if (!problems.contains(problem)) {
                problems.add(problem);
            }
            if (problems.size() == MAX_REPORTED) {
                break;
            }
        }
        throw new InvalidToolArgumentException("Invalid arguments: " + String.join("; ", problems) + ".");
    }

    private String describe(Error error) {
        String keyword = error.getKeyword();
        String argument = argumentOf(error, keyword);
        JsonNode rule = argument == null ? JSON.missingNode() : properties.path(argument);
        String subject = argument == null ? "arguments" : argument;
        return switch (keyword) {
            case "required" -> subject + " is required";
            case "additionalProperties", "unevaluatedProperties", "propertyNames" ->
                    "unknown arguments are not allowed";
            case "type" -> subject + " has the wrong type" + (rule.has("type") ? " (expected " + rule.get("type") + ")" : "");
            case "maxLength" -> subject + " is longer than " + rule.path("maxLength").asString("allowed") + " characters";
            case "minLength" -> subject + " is shorter than " + rule.path("minLength").asString("allowed") + " characters";
            case "pattern" -> subject + " does not match the required format"
                    + (rule.has("pattern") ? " " + rule.get("pattern").asString() : "");
            case "minimum", "exclusiveMinimum" -> subject + " is below the minimum " + rule.path(keyword).asString("allowed");
            case "maximum", "exclusiveMaximum" -> subject + " is above the maximum " + rule.path(keyword).asString("allowed");
            case "enum", "const" -> subject + " is not one of the allowed values"
                    + (rule.has("enum") ? " " + rule.get("enum") : "");
            default -> subject + " is not valid";
        };
    }

    /** The declared top-level argument the error is about, or {@code null} (undeclared names are not echoed). */
    private String argumentOf(Error error, String keyword) {
        String name = null;
        if ("required".equals(keyword)) {
            name = error.getProperty();
        } else if (error.getInstanceLocation() != null && error.getInstanceLocation().getNameCount() > 0) {
            name = error.getInstanceLocation().getName(0);
        }
        return name != null && declared.contains(name) ? name : null;
    }
}
