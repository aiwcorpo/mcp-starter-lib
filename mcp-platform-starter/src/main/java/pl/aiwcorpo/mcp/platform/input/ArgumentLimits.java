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
 *   <li>text ({@code String}, dates and the like that Spring AI describes as {@code string}):
 *       {@link #maxLength()} is required (or comes from {@link #contractPattern()}); {@link #minLength()} and
 *       {@link #pattern()} are optional;</li>
 *   <li>integers and decimals: {@link #minimum()} and {@link #maximum()} are both required;</li>
 *   <li>lists and arrays: {@link #maxItems()} is required; the other limits describe the elements (scalars);
 *       a list of records has its limits on the record components;</li>
 *   <li>records (a filter object): no limits on the parameter itself, they go on the record components, which
 *       take this annotation too. A component that may be absent is marked {@code @Nullable} (Spring AI then
 *       leaves it out of {@code required});</li>
 *   <li>{@code Map} cannot be bounded and stops the server: use a record or a list of records.</li>
 * </ul>
 */
@Documented
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
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

    /**
     * Name of a string definition in the contracts' {@code common.schema.json} ({@code $defs}), for example
     * {@code JiraIssueKey} or {@code GitLabProjectPath}: its {@code pattern} (and its {@code maxLength}, when
     * {@link #maxLength()} is not given) is published instead of a copy typed into the server. Cannot be combined
     * with {@link #pattern()}. An unknown name, or a definition without a pattern, stops the server.
     */
    String contractPattern() default "";

    /** JSON Schema {@code maxItems} of a list or array; -1 = none, which {@link SchemaLimitedToolCallbacks} refuses. */
    int maxItems() default -1;

    /** JSON Schema {@code minItems} of a list or array; -1 = none. */
    int minItems() default -1;

    /** JSON Schema {@code minimum}; {@link Long#MIN_VALUE} = none. */
    long minimum() default Long.MIN_VALUE;

    /** JSON Schema {@code maximum}; {@link Long#MAX_VALUE} = none. */
    long maximum() default Long.MAX_VALUE;
}
