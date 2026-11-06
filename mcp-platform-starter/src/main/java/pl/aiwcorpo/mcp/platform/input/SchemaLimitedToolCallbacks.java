package pl.aiwcorpo.mcp.platform.input;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ReflectionUtils;
import pl.aiwcorpo.mcp.cedar.entities.ResourceTypeDeclaration;
import pl.aiwcorpo.mcp.cedar.entities.ResourceTypes;
import pl.aiwcorpo.mcp.platform.policy.ServerPolicies;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The server's tool registration: builds the callbacks the way {@code MethodToolCallbackProvider} does and adds
 * to each generated {@code inputSchema} the {@link ArgumentLimits} of its parameters and
 * {@code additionalProperties: false}. The callbacks stay {@link MethodToolCallback}s, so the platform still
 * reads {@code @ToolRisk} from the method and wraps them (audit, C10, argument validation).
 *
 * <pre>{@code
 * @Bean
 * ToolCallbackProvider tools(JiraTools jiraTools) {
 *     return SchemaLimitedToolCallbacks.of(jiraTools);
 * }
 * }</pre>
 *
 * Fail-closed at startup, at every depth (record components, list elements): a text value without
 * {@code maxLength}, a list without {@code maxItems}, a number without {@code minimum} and {@code maximum}, a
 * {@code Map}, an object that is not a record, limits of the wrong kind, or a parameter missing from the
 * generated schema (compile with {@code -parameters}) stop the server. An optional record component is marked
 * {@code @Nullable}; Spring AI then leaves it out of {@code required}.
 */
public final class SchemaLimitedToolCallbacks {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode CONTRACT_DEFINITIONS = contractDefinitions();
    private static final int MAX_DEPTH = 6;
    private static volatile ResourceTypes declaredTypes;

    private SchemaLimitedToolCallbacks() {
    }

    public static ToolCallbackProvider of(Object... toolObjects) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Object toolObject : toolObjects) {
            for (Method method : ReflectionUtils.getUniqueDeclaredMethods(AopUtils.getTargetClass(toolObject))) {
                if (method.isAnnotationPresent(Tool.class)) {
                    callbacks.add(callback(toolObject, method));
                }
            }
        }
        if (callbacks.isEmpty()) {
            throw new IllegalStateException("no @Tool methods in the tool objects");
        }
        callbacks.sort(Comparator.comparing(callback -> callback.getToolDefinition().name()));
        return ToolCallbackProvider.from(callbacks);
    }

    private static MethodToolCallback callback(Object toolObject, Method method) {
        String schema = withLimits(ToolDefinitions.from(method).inputSchema(), method);
        return MethodToolCallback.builder()
                .toolDefinition(ToolDefinitions.builder(method).inputSchema(schema).build())
                .toolMetadata(ToolMetadata.from(method))
                .toolMethod(method)
                .toolObject(toolObject)
                .toolCallResultConverter(ToolUtils.getToolCallResultConverter(method))
                .build();
    }

    static String withLimits(String inputSchema, Method method) {
        ObjectNode schema = (ObjectNode) JSON.readTree(inputSchema);
        schema.put("additionalProperties", false);
        JsonNode properties = schema.path("properties");
        for (Parameter parameter : method.getParameters()) {
            if (ToolContext.class.isAssignableFrom(parameter.getType())) {
                continue;
            }
            String where = method.getName() + "(" + parameter.getName() + ")";
            if (!(properties.path(parameter.getName()) instanceof ObjectNode property)) {
                throw new IllegalStateException("@Tool " + where + ": no property in the generated schema "
                        + "(compile with -parameters)");
            }
            limit(property, parameter.getParameterizedType(), parameter.getAnnotation(ArgumentLimits.class), where, 0);
        }
        return JSON.writeValueAsString(schema);
    }

    /** Publishes the limits for one value of {@code javaType}, recursing into records and list elements. */
    private static void limit(ObjectNode property, Type javaType, ArgumentLimits limits, String where, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("@Tool " + where + ": nested deeper than " + MAX_DEPTH + " levels");
        }
        Class<?> raw = rawClass(javaType);
        if (Map.class.isAssignableFrom(raw)) {
            throw new IllegalStateException("@Tool " + where + ": a Map has no bounded schema (any number of "
                    + "entries, any keys); use a record with named fields or a list of records");
        }
        String type = schemaType(property);
        if (property.has("enum")) {
            noLimits(limits, where, "an enum");
        } else if ("string".equals(type)) {
            Limits text = textLimits(limits, where);
            property.put("maxLength", text.maxLength());
            if (text.minLength() >= 0) {
                property.put("minLength", text.minLength());
            }
            if (text.pattern() != null) {
                property.put("pattern", text.pattern());
            }
        } else if ("integer".equals(type) || "number".equals(type)) {
            numberLimits(limits, where, type, property);
        } else if ("boolean".equals(type)) {
            noLimits(limits, where, "a boolean");
        } else if ("array".equals(type)) {
            listLimits(property, javaType, raw, limits, where, depth);
        } else if ("object".equals(type)) {
            recordLimits(property, raw, limits, where, depth);
        } else {
            throw new IllegalStateException("@Tool " + where + ": type " + javaType.getTypeName() + " has no "
                    + "bounded JSON schema; use String, numbers, booleans, enums, records and lists of them");
        }
    }

    private static void listLimits(ObjectNode property, Type javaType, Class<?> raw, ArgumentLimits limits,
                                   String where, int depth) {
        if (limits == null || limits.maxItems() < 0) {
            throw new IllegalStateException("@Tool " + where + ": a list needs @ArgumentLimits(maxItems = ...), "
                    + "otherwise the model may send any number of elements");
        }
        property.put("maxItems", limits.maxItems());
        if (limits.minItems() >= 0) {
            property.put("minItems", limits.minItems());
        }
        Type element = raw.isArray() ? raw.getComponentType()
                : javaType instanceof ParameterizedType parameterized ? parameterized.getActualTypeArguments()[0] : null;
        if (element == null || !(property.path("items") instanceof ObjectNode items)) {
            throw new IllegalStateException("@Tool " + where + ": the element type of the list is unknown");
        }
        if ("array".equals(schemaType(items))) {
            throw new IllegalStateException("@Tool " + where + ": a list of lists has no bounded schema");
        }
        limit(items, element, "object".equals(schemaType(items)) ? null : limits, where + "[]", depth + 1);
    }

    private static void recordLimits(ObjectNode property, Class<?> raw, ArgumentLimits limits, String where, int depth) {
        if (!raw.isRecord()) {
            throw new IllegalStateException("@Tool " + where + ": an object argument must be a record (type "
                    + raw.getName() + "), so that every field can carry its limits");
        }
        noLimits(limits, where, "a record (put the limits on its components)");
        property.put("additionalProperties", false);
        for (RecordComponent component : raw.getRecordComponents()) {
            String name = component.getName();
            if (!(property.path("properties").path(name) instanceof ObjectNode field)) {
                throw new IllegalStateException("@Tool " + where + "." + name + ": no property in the generated schema");
            }
            limit(field, component.getGenericType(), component.getAnnotation(ArgumentLimits.class),
                    where + "." + name, depth + 1);
        }
    }

    private static void numberLimits(ArgumentLimits limits, String where, String type, ObjectNode property) {
        if (limits == null || limits.minimum() == Long.MIN_VALUE || limits.maximum() == Long.MAX_VALUE) {
            throw new IllegalStateException("@Tool " + where + ": " + (type.startsWith("integer") ? "an " : "a ") + type
                    + " argument needs "
                    + "@ArgumentLimits(minimum = ..., maximum = ...), otherwise the model may send any value");
        }
        if (limits.minLength() >= 0 || limits.maxLength() >= 0 || !limits.pattern().isEmpty()
                || !limits.contractPattern().isEmpty() || !limits.resourceType().isEmpty() || limits.mentionFiltered()) {
            throw new IllegalStateException("@ArgumentLimits on " + where + ": text limits only on text "
                    + "arguments, minimum/maximum only on numbers (schema type: " + type + ")");
        }
        property.put("minimum", limits.minimum());
        property.put("maximum", limits.maximum());
    }

    private static void noLimits(ArgumentLimits limits, String where, String what) {
        if (limits == null) {
            return;
        }
        boolean any = limits.minLength() >= 0 || limits.maxLength() >= 0 || !limits.pattern().isEmpty()
                || !limits.contractPattern().isEmpty() || !limits.resourceType().isEmpty() || limits.minimum() != Long.MIN_VALUE
                || limits.maximum() != Long.MAX_VALUE || limits.maxItems() >= 0 || limits.minItems() >= 0
                || limits.mentionFiltered();
        if (any) {
            throw new IllegalStateException("@ArgumentLimits on " + where + ": " + what + " takes no limits here");
        }
    }

    /** The JSON schema type, ignoring {@code null} in a union such as {@code ["string", "null"]} (Optional). */
    private static String schemaType(JsonNode property) {
        JsonNode type = property.path("type");
        if (type.isString()) {
            return type.asString();
        }
        if (type.isArray()) {
            for (JsonNode candidate : type) {
                if (!"null".equals(candidate.asString())) {
                    return candidate.asString();
                }
            }
        }
        return null;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz) {
            return clazz;
        }
        return Object.class;
    }

    private record Limits(int minLength, int maxLength, String pattern) {
    }

    /** The limits of a text argument; {@code maxLength} is mandatory, directly or from the contract's definition. */
    private static Limits textLimits(ArgumentLimits limits, String where) {
        if (limits == null) {
            throw new IllegalStateException("@Tool " + where + ": a text argument needs "
                    + "@ArgumentLimits(maxLength = ...), otherwise the model may send any amount of text");
        }
        if (limits.minimum() != Long.MIN_VALUE || limits.maximum() != Long.MAX_VALUE) {
            throw new IllegalStateException("@ArgumentLimits on " + where + ": minimum/maximum on a text argument");
        }
        String pattern = limits.pattern().isEmpty() ? null : limits.pattern();
        int maxLength = limits.maxLength();
        if (!limits.resourceType().isEmpty()) {
            if (pattern != null || !limits.contractPattern().isEmpty()) {
                throw new IllegalStateException("@ArgumentLimits on " + where + ": resourceType with pattern or contractPattern");
            }
            ResourceTypeDeclaration declaration = declaredTypes().declaration(limits.resourceType()).orElseThrow(
                    () -> new IllegalStateException("@ArgumentLimits on " + where + ": '" + limits.resourceType()
                            + "' is not declared in mcp/policies/resource-types.json"));
            pattern = declaration.idPattern();
            if (maxLength < 0) {
                maxLength = ResourceTypes.MAX_ID_LENGTH;
            }
        }
        if (!limits.contractPattern().isEmpty()) {
            if (pattern != null) {
                throw new IllegalStateException("@ArgumentLimits on " + where + ": pattern and contractPattern together");
            }
            JsonNode definition = CONTRACT_DEFINITIONS.path(limits.contractPattern());
            pattern = definition.path("pattern").asString(null);
            if (pattern == null || !"string".equals(definition.path("type").asString(null))) {
                throw new IllegalStateException("@ArgumentLimits on " + where + ": '" + limits.contractPattern()
                        + "' is not a string definition with a pattern in common.schema.json");
            }
            if (maxLength < 0) {
                maxLength = definition.path("maxLength").asInt(-1);
            }
        }
        if (maxLength < 0) {
            throw new IllegalStateException("@Tool " + where + ": a text argument needs "
                    + "@ArgumentLimits(maxLength = ...), otherwise the model may send any amount of text");
        }
        return new Limits(limits.minLength(), maxLength, pattern);
    }

    /** The declarations of the server's own jar, read once: the tool schemas are built before the Spring context exists. */
    private static ResourceTypes declaredTypes() {
        ResourceTypes types = declaredTypes;
        if (types == null) {
            types = ServerPolicies.load(SchemaLimitedToolCallbacks.class.getClassLoader()).resourceTypes();
            declaredTypes = types;
        }
        return types;
    }

    /**
     * A definition of {@code common.schema.json} of the contracts jar by name: the stable API of CC-079 that
     * {@link ArgumentLimits#contractPattern()} reads; missing nodes for an unknown name.
     */
    static JsonNode contractDefinition(String name) {
        return CONTRACT_DEFINITIONS.path(name);
    }

    private static JsonNode contractDefinitions() {
        String resource = "/mcp-contracts/schemas/common.schema.json";
        try (java.io.InputStream in = SchemaLimitedToolCallbacks.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing: mcp-contracts must be on the classpath");
            }
            return JSON.readTree(in).path("$defs");
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
