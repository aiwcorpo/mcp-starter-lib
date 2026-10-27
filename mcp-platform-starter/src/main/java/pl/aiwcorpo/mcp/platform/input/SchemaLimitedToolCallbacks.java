package pl.aiwcorpo.mcp.platform.input;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
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
 * Fail-closed at startup: a text parameter without {@code maxLength}, limits on a parameter of another type or
 * a parameter missing from the generated schema (compile with {@code -parameters}) stop the server.
 */
public final class SchemaLimitedToolCallbacks {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode CONTRACT_DEFINITIONS = contractDefinitions();
    private static final Set<String> INTEGER_TYPES = Set.of("integer");

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
            ArgumentLimits annotation = parameter.getAnnotation(ArgumentLimits.class);
            String type = property.path("type").asString(null);
            if ("string".equals(type) && !property.has("enum")) {
                Limits limits = textLimits(annotation, where);
                property.put("maxLength", limits.maxLength());
                if (limits.minLength() >= 0) {
                    property.put("minLength", limits.minLength());
                }
                if (limits.pattern() != null) {
                    property.put("pattern", limits.pattern());
                }
            } else if (annotation != null) {
                if (!INTEGER_TYPES.contains(type) || annotation.minLength() >= 0 || annotation.maxLength() >= 0
                        || !annotation.pattern().isEmpty() || !annotation.contractPattern().isEmpty()) {
                    throw new IllegalStateException("@ArgumentLimits on " + where + ": text limits only on text "
                            + "arguments, minimum/maximum only on integers (schema type: " + type + ")");
                }
                if (annotation.minimum() != Long.MIN_VALUE) {
                    property.put("minimum", annotation.minimum());
                }
                if (annotation.maximum() != Long.MAX_VALUE) {
                    property.put("maximum", annotation.maximum());
                }
            }
        }
        return JSON.writeValueAsString(schema);
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
