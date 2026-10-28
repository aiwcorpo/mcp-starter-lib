package pl.aiwcorpo.mcp.platform.input;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The arguments of a {@code @Tool} method that a mention filter of the server reads, marked with
 * {@code @ArgumentLimits(mentionFiltered = true)}: text the server passes on to a system that turns {@code @name}
 * into a notification. In such an argument the platform refuses the full width ASCII characters U+FF01..U+FF5E as
 * {@code invalid_argument} (CC-121), because "@all" written with them is a mention the server's filter does not see.
 *
 * <p>A path is the name of the parameter, then {@code .component} for a record component and {@code []} for a list
 * element: {@code comment.body}, {@code labels[]}.
 */
final class MentionFilteredArguments {

    private static final int MAX_DEPTH = 6;

    private MentionFilteredArguments() {
    }

    /** The paths in {@code method} that are marked; empty when none is. */
    static Set<String> of(Method method) {
        Set<String> paths = new LinkedHashSet<>();
        for (Parameter parameter : method.getParameters()) {
            if (!ToolContext.class.isAssignableFrom(parameter.getType())) {
                collect(parameter.getName(), parameter.getParameterizedType(), parameter.getAnnotation(ArgumentLimits.class),
                        paths, 0);
            }
        }
        return Set.copyOf(paths);
    }

    private static void collect(String path, Type type, ArgumentLimits limits, Set<String> paths, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        Class<?> raw = type instanceof Class<?> clazz ? clazz
                : type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> clazz
                        ? clazz : Object.class;
        if (raw == String.class) {
            if (limits != null && limits.mentionFiltered()) {
                paths.add(path);
            }
        } else if (raw.isArray()) {
            collect(path + "[]", raw.getComponentType(), limits, paths, depth + 1);
        } else if (java.util.Collection.class.isAssignableFrom(raw) && type instanceof ParameterizedType parameterized) {
            collect(path + "[]", parameterized.getActualTypeArguments()[0], limits, paths, depth + 1);
        } else if (raw.isRecord()) {
            for (RecordComponent component : raw.getRecordComponents()) {
                collect(path + "." + component.getName(), component.getGenericType(),
                        component.getAnnotation(ArgumentLimits.class), paths, depth + 1);
            }
        }
    }
}
