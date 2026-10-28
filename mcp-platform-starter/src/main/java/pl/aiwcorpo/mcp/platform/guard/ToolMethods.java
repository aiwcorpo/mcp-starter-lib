package pl.aiwcorpo.mcp.platform.guard;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallback;

/** Finds the {@code @Tool} method behind a callback. */
public final class ToolMethods {

    private static final Logger LOG = LoggerFactory.getLogger(ToolMethods.class);

    private ToolMethods() {
    }

    /**
     * The {@code @Tool} method behind a callback. Spring AI keeps the reflective Method private and offers no
     * accessor. Found by type rather than by field name, so a rename upstream fails startup (tool counts as
     * unflagged) instead of unflagging.
     */
    public static Optional<Method> methodOf(ToolCallback callback) {
        if (!(callback instanceof MethodToolCallback)) {
            return Optional.empty();
        }
        for (Field field : MethodToolCallback.class.getDeclaredFields()) {
            if (field.getType() != Method.class) {
                continue;
            }
            try {
                field.setAccessible(true);
                return Optional.ofNullable((Method) field.get(callback));
            } catch (ReflectiveOperationException | RuntimeException ex) {
                LOG.warn("event=tool_method_unreadable tool=\"{}\" reason=\"{}\"",
                        callback.getToolDefinition().name(), ex.toString());
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
