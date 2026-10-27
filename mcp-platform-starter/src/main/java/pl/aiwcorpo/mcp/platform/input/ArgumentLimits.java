package pl.aiwcorpo.mcp.platform.input;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Limits of a tool argument, published in the tool's {@code inputSchema} by {@link SchemaLimitedToolCallbacks}
 * (Spring AI generates only type and description from {@code @Tool} parameters). The platform enforces the
 * schema on every call ({@code ToolInputValidator}), so the limits are checked before the tool runs; the tool
 * should still validate what it relies on, with the same constants.
 *
 * <ul>
 *   <li>text ({@code String}): {@link #maxLength()} is required; {@link #minLength()} and {@link #pattern()} are
 *       optional;</li>
 *   <li>integers: {@link #minimum()} and {@link #maximum()}.</li>
 * </ul>
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ArgumentLimits {

    /** JSON Schema {@code minLength}; -1 = none. */
    int minLength() default -1;

    /** JSON Schema {@code maxLength}; -1 = none, which {@link SchemaLimitedToolCallbacks} refuses for text. */
    int maxLength() default -1;

    /**
     * JSON Schema {@code pattern} (ECMA 262, unanchored unless written with {@code ^} and {@code $}, so always
     * anchor it); empty = none.
     */
    String pattern() default "";

    /** JSON Schema {@code minimum}; {@link Long#MIN_VALUE} = none. */
    long minimum() default Long.MIN_VALUE;

    /** JSON Schema {@code maximum}; {@link Long#MAX_VALUE} = none. */
    long maximum() default Long.MAX_VALUE;
}
