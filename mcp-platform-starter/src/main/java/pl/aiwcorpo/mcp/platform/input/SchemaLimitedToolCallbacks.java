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
            ArgumentLimits limits = parameter.getAnnotation(ArgumentLimits.class);
            String type = property.path("type").asString(null);
            if ("string".equals(type)) {
                if (limits == null || limits.maxLength() < 0) {
                    throw new IllegalStateException("@Tool " + where + ": a text argument needs "
                            + "@ArgumentLimits(maxLength = ...), otherwise the model may send any amount of text");
                }
                requireNoNumericLimits(limits, where);
                property.put("maxLength", limits.maxLength());
                if (limits.minLength() >= 0) {
                    property.put("minLength", limits.minLength());
                }
                if (!limits.pattern().isEmpty()) {
                    property.put("pattern", limits.pattern());
                }
            } else if (limits != null) {
                if (!INTEGER_TYPES.contains(type) || limits.minLength() >= 0 || limits.maxLength() >= 0
                        || !limits.pattern().isEmpty()) {
                    throw new IllegalStateException("@ArgumentLimits on " + where + ": text limits only on text "
                            + "arguments, minimum/maximum only on integers (schema type: " + type + ")");
                }
                if (limits.minimum() != Long.MIN_VALUE) {
                    property.put("minimum", limits.minimum());
                }
                if (limits.maximum() != Long.MAX_VALUE) {
                    property.put("maximum", limits.maximum());
                }
            }
        }
        return JSON.writeValueAsString(schema);
    }

    private static void requireNoNumericLimits(ArgumentLimits limits, String where) {
        if (limits.minimum() != Long.MIN_VALUE || limits.maximum() != Long.MAX_VALUE) {
            throw new IllegalStateException("@ArgumentLimits on " + where + ": minimum/maximum on a text argument");
        }
    }
}
