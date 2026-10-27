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
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

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
    private static final int MAX_TEXT_DEPTH = 12;
    private static final ToolInputValidator NONE = new ToolInputValidator(null, Set.of(), null, null);

    private final Schema schema;
    private final Set<String> declared;
    private final JsonNode properties;
    private final JsonNode root;

    private ToolInputValidator(Schema schema, Set<String> declared, JsonNode properties, JsonNode root) {
        this.root = root;
        this.schema = schema;
        this.declared = declared;
        this.properties = properties;
    }

    /**
     * @throws IllegalStateException when the schema cannot be used, or (with {@link ToolInputPolicy#requireBoundedSchema})
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
        if (!closed) {
            // Outside dev open() throws; in dev the schema is still enforced as far as it goes.
            open(name, "has an inputSchema without type: object and additionalProperties: false", policy);
        }
        List<String> unbounded = InputSchemaBounds.problems(root);
        if (!unbounded.isEmpty() && closed) {
            unbounded(name, unbounded, policy);
        }
        return compiled(name, root);
    }

    private static void unbounded(String tool, List<String> problems, ToolInputPolicy policy) {
        String message = "Tool '" + tool + "' has an inputSchema with unbounded levels: " + String.join("; ", problems)
                + ". Every level needs a limit (@ArgumentLimits on arguments and on record components, "
                + "SchemaLimitedToolCallbacks.of(...) publishes them); maps cannot be bounded, use a record "
                + "or a list of records.";
        if (policy.requireBoundedSchema()) {
            throw new IllegalStateException(message);
        }
        LOG.warn("event=tool_schema_open tool=\"{}\" detail=\"{}\" profile=dev", tool, message.replace('"', '\''));
    }

    private static ToolInputValidator open(String tool, String what, ToolInputPolicy policy) {
        String message = "Tool '" + tool + "' " + what + ": arguments the schema does not declare would reach the "
                + "tool. Declare the arguments (@ToolParam) and publish additionalProperties: false "
                + "(SchemaLimitedToolCallbacks.of(...) adds it).";
        if (policy.requireBoundedSchema()) {
            throw new IllegalStateException(message);
        }
        LOG.warn("event=tool_schema_open tool=\"{}\" detail=\"{}\" profile=dev", tool, message);
        return NONE;
    }

    private static ToolInputValidator compiled(String name, JsonNode root) {
        Schema schema;
        JsonNode enforced = root.deepCopy();
        anchorPatterns(enforced);
        try {
            schema = REGISTRY.getSchema(enforced);
            schema.initializeValidators();
        } catch (RuntimeException ex) {
            throw new IllegalStateException("Tool '" + name + "': inputSchema cannot be compiled ("
                    + ex.getClass().getSimpleName() + ")", ex);
        }
        Set<String> declared = new LinkedHashSet<>(root.path("properties").propertyNames());
        return new ToolInputValidator(schema, declared, root.path("properties"), root);
    }

    /**
     * Java's {@code $} also matches before a final line terminator, so {@code ^[A-Z]+-[0-9]+$} would accept
     * {@code "ABC-1\n"}; in ECMA 262 (what the schema means) it does not. Every unescaped {@code $} outside a
     * character class becomes {@code \z} (end of input), which is what {@code $} means without the multiline flag.
     */
    private static void anchorPatterns(JsonNode node) {
        if (node instanceof ObjectNode object) {
            for (String name : List.copyOf(object.propertyNames())) {
                JsonNode child = object.get(name);
                if ("pattern".equals(name) && child.isString()) {
                    object.put(name, anchorEnd(child.asString()));
                } else {
                    anchorPatterns(child);
                }
            }
        } else if (node.isArray()) {
            node.forEach(ToolInputValidator::anchorPatterns);
        }
    }

    static String anchorEnd(String pattern) {
        StringBuilder out = new StringBuilder(pattern.length() + 4);
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                out.append(c).append(pattern.charAt(++i));
                continue;
            }
            if (c == '[') {
                inClass = true;
            } else if (c == ']') {
                inClass = false;
            } else if (c == '$' && !inClass) {
                out.append("\\z");
                continue;
            }
            out.append(c);
        }
        return out.toString();
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
        List<Error> errors;
        try {
            errors = schema.validate(arguments);
        } catch (JacksonException ex) {
            // The schema library converts numbers to long for minimum/maximum and throws on 1e30.
            throw new InvalidToolArgumentException("Invalid arguments: a number is out of range.");
        }
        List<String> controls = new ArrayList<>();
        if (arguments.isObject()) {
            arguments.propertyNames().forEach(name -> textProblems(arguments.get(name), properties.get(name),
                    declared.contains(name) ? name : "an argument", controls, 0));
        }
        if (errors.isEmpty() && controls.isEmpty()) {
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
        for (String control : controls) {
            if (problems.size() < MAX_REPORTED && !problems.contains(control)) {
                problems.add(control);
            }
        }
        throw new InvalidToolArgumentException("Invalid arguments: " + String.join("; ", problems) + ".");
    }

    /**
     * Text hygiene ({@link TextHygiene}) of every string in the arguments, judged by the rule of the schema node
     * that describes that very value (properties, list items, map values, {@code $ref}), not by the rule of the
     * top-level argument it sits in. A pattern marks a formatted value (a key, a path): there a line break and
     * every format character are refused unless the pattern names them itself. A node that cannot be determined
     * counts as formatted, the strictest reading.
     */
    private void textProblems(JsonNode value, JsonNode node, String top, List<String> found, int depth) {
        JsonNode rule = resolve(node);
        if (value.isString()) {
            boolean formatted = !allowsLineBreaks(node);
            TextHygiene.Problem problem = TextHygiene.firstProblem(value.asString(), formatted);
            if (problem != null) {
                found.add(top + " contains " + problem.description());
            }
        } else if (depth < MAX_TEXT_DEPTH && value.isObject()) {
            value.propertyNames().forEach(name -> {
                JsonNode child = rule == null ? null : rule.path("properties").get(name);
                if (child == null && rule != null && rule.path("additionalProperties").isObject()) {
                    child = rule.get("additionalProperties");
                }
                textProblems(value.get(name), child, top, found, depth + 1);
            });
        } else if (depth < MAX_TEXT_DEPTH && value.isArray()) {
            JsonNode items = rule == null ? null : rule.get("items");
            value.forEach(element -> textProblems(element, items, top, found, depth + 1));
        } else if (value.isObject() || value.isArray()) {
            found.add(top + " is nested too deeply");
        }
    }

    /**
     * Whether the value may hold tab, newline and carriage return: only when the node is plain free text, or its
     * pattern (on the node and on every {@code $ref} target in the chain) names them itself. Anything unclear
     * (node missing, unresolvable {@code $ref}, a combinator) counts as formatted, the strictest reading.
     */
    private boolean allowsLineBreaks(JsonNode node) {
        JsonNode current = node;
        for (int hops = 0; hops < 8; hops++) {
            if (current == null || !current.isObject()) {
                return false;
            }
            for (String keyword : List.of("allOf", "anyOf", "oneOf", "not", "if")) {
                if (current.has(keyword)) {
                    return false;
                }
            }
            if (current.has("pattern")) {
                if (!allowsWhitespaceControls(current.get("pattern").asString())) {
                    return false;
                }
            }
            String ref = current.path("$ref").asString(null);
            if (ref == null) {
                return true;
            }
            JsonNode target = ref.startsWith("#") ? root.at(ref.substring(1)) : null;
            current = target == null || target.isMissingNode() ? null : target;
        }
        return false;
    }

    /** Follows local {@code $ref}s; {@code null} when the node is absent, not an object or cannot be resolved. */
    private JsonNode resolve(JsonNode node) {
        JsonNode current = node;
        for (int hops = 0; current != null && hops < 8; hops++) {
            if (!current.isObject()) {
                return null;
            }
            String ref = current.path("$ref").asString(null);
            if (ref == null) {
                return current;
            }
            JsonNode target = ref.startsWith("#") ? root.at(ref.substring(1)) : null;
            current = target == null || target.isMissingNode() ? null : target;
        }
        return null;
    }

    private static boolean allowsWhitespaceControls(String pattern) {
        return pattern.matches("(?s).*(\\\\[nrtsxuc]|\\\\p\\{Cntrl\\}|[\\x00-\\x1f]).*");
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
