package pl.aiwcorpo.mcp.platform.text;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code outputSchema} of a tool whose result contains {@link UntrustedText}: JSON Schema built from the
 * return type of the {@code @Tool} method (records, lists and arrays of them, strings, numbers, booleans, enums,
 * dates), with {@link UntrustedText} written as the contract's definition. A tool whose result holds no
 * {@code UntrustedText} gets none (the platform adds no output schema where it has no rule to enforce).
 *
 * <p>The schema is published in {@code tools/list}, so it is part of the {@code definitionHash} the gateway pins
 * (C6, CC-004). The result must be a record: MCP requires an object as the root of an output schema.
 */
public final class ToolOutputSchemas {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_DEPTH = 8;

    private ToolOutputSchemas() {
    }

    /** The output schema for the tool method's return type, empty when it holds no {@link UntrustedText}. */
    public static Optional<ObjectNode> forMethod(Method method) {
        Type type = method.getGenericReturnType();
        if (!contains(type, 0)) {
            return Optional.empty();
        }
        if (!(type instanceof Class<?> clazz) || !clazz.isRecord()) {
            throw new IllegalStateException("@Tool " + method.getName() + " returns UntrustedText inside "
                    + type.getTypeName() + ": the result of such a tool must be a record (MCP output schemas are "
                    + "objects)");
        }
        return Optional.of((ObjectNode) schemaOf(type, method.getName(), 0));
    }

    private static boolean contains(Type type, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        Class<?> raw = rawClass(type);
        if (raw == UntrustedText.class) {
            return true;
        }
        if (raw.isArray()) {
            return contains(raw.getComponentType(), depth + 1);
        }
        if (Collection.class.isAssignableFrom(raw) || Optional.class.isAssignableFrom(raw)) {
            return type instanceof ParameterizedType p && contains(p.getActualTypeArguments()[0], depth + 1);
        }
        if (raw.isRecord()) {
            for (RecordComponent component : raw.getRecordComponents()) {
                if (contains(component.getGenericType(), depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonNode schemaOf(Type type, String where, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("@Tool " + where + ": result nested deeper than " + MAX_DEPTH + " levels");
        }
        Class<?> raw = rawClass(type);
        ObjectNode schema = JSON.createObjectNode();
        if (raw == UntrustedText.class) {
            return contractUntrustedText();
        } else if (raw == String.class || raw == char.class || raw == Character.class || raw == UUID.class
                || Temporal.class.isAssignableFrom(raw) || raw == java.util.Date.class) {
            schema.put("type", "string");
        } else if (raw.isEnum()) {
            schema.put("type", "string");
            var array = schema.putArray("enum");
            for (Object constant : raw.getEnumConstants()) {
                array.add(((Enum<?>) constant).name());
            }
        } else if (raw == boolean.class || raw == Boolean.class) {
            schema.put("type", "boolean");
        } else if (raw == int.class || raw == long.class || raw == short.class || raw == byte.class
                || raw == Integer.class || raw == Long.class || raw == Short.class || raw == Byte.class
                || raw == BigInteger.class) {
            schema.put("type", "integer");
        } else if (raw == double.class || raw == float.class || raw == Double.class || raw == Float.class
                || raw == BigDecimal.class) {
            schema.put("type", "number");
        } else if (raw.isArray()) {
            schema.put("type", "array");
            schema.set("items", schemaOf(raw.getComponentType(), where + "[]", depth + 1));
        } else if (Collection.class.isAssignableFrom(raw) && type instanceof ParameterizedType p) {
            schema.put("type", "array");
            schema.set("items", schemaOf(p.getActualTypeArguments()[0], where + "[]", depth + 1));
        } else if (Optional.class.isAssignableFrom(raw) && type instanceof ParameterizedType p) {
            return nullable(schemaOf(p.getActualTypeArguments()[0], where, depth + 1));
        } else if (raw.isRecord()) {
            schema.put("type", "object");
            ObjectNode properties = schema.putObject("properties");
            List<String> required = new ArrayList<>();
            for (RecordComponent component : raw.getRecordComponents()) {
                JsonNode property = schemaOf(component.getGenericType(), where + "." + component.getName(), depth + 1);
                if (isNullable(component)) {
                    property = nullable(property);
                } else {
                    required.add(component.getName());
                }
                properties.set(component.getName(), property);
            }
            var requiredArray = schema.putArray("required");
            required.forEach(requiredArray::add);
            schema.put("additionalProperties", false);
        } else {
            throw new IllegalStateException("@Tool " + where + ": cannot describe the result type " + type.getTypeName()
                    + " in an output schema; use records, lists, strings, numbers, booleans and enums");
        }
        return schema;
    }

    /** A {@code @Nullable} of any library: on the component, its accessor (Spring's) or its type (JSpecify's). */
    private static boolean isNullable(RecordComponent component) {
        return Stream.of(component.getAnnotations(), component.getAccessor().getAnnotations(),
                        component.getAnnotatedType().getAnnotations())
                .flatMap(Arrays::stream)
                .anyMatch(annotation -> annotation.annotationType().getSimpleName().equals("Nullable"));
    }

    private static JsonNode nullable(JsonNode schema) {
        ObjectNode wrapper = JSON.createObjectNode();
        var anyOf = wrapper.putArray("anyOf");
        anyOf.add(schema);
        anyOf.addObject().put("type", "null");
        return wrapper;
    }

    /** {@code UntrustedText} from {@code common.schema.json} of the contracts jar, as a standalone schema. */
    private static JsonNode contractUntrustedText() {
        String resource = "/mcp-contracts/schemas/common.schema.json";
        try (java.io.InputStream in = ToolOutputSchemas.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing: mcp-contracts must be on the classpath");
            }
            JsonNode definition = JSON.readTree(in).path("$defs").path("UntrustedText");
            if (!definition.isObject()) {
                throw new IllegalStateException("UntrustedText is not defined in " + resource);
            }
            return definition.deepCopy();
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
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
}
