package pl.aiwcorpo.mcp.platform.resource;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads {@link ResourceRef} from a {@code @Tool} method: the arguments, and the shape of the result. */
public final class ResourceRefs {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> TYPES = contractTypes();
    private static final int MAX_DEPTH = 6;

    private ResourceRefs() {
    }

    /**
     * The resource arguments of {@code method}, in parameter order.
     *
     * @throws IllegalStateException for a type outside the contracts, or a parameter that cannot carry identifiers
     */
    public static List<ResourceBinding> arguments(Method method) {
        List<ResourceBinding> bindings = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            ResourceRef ref = parameter.getAnnotation(ResourceRef.class);
            if (ref == null || ToolContext.class.isAssignableFrom(parameter.getType())) {
                continue;
            }
            String where = "@Tool " + method.getName() + "(" + parameter.getName() + ")";
            checkType(ref, where);
            if (!identifiers(parameter.getParameterizedType())) {
                throw new IllegalStateException(where + ": @ResourceRef needs a String, an integer, or a list or array of them, not "
                        + parameter.getParameterizedType().getTypeName());
            }
            bindings.add(new ResourceBinding(parameter.getName(), ref.type(), ref.resolver()));
        }
        return List.copyOf(bindings);
    }

    /**
     * The resource components of the result type of {@code method}, as the shape the platform filters; empty when the
     * result holds none.
     */
    public static ResultShape result(Method method) {
        return shape(method.getGenericReturnType(), method.getName() + " result", 0);
    }

    private static void checkType(ResourceRef ref, String where) {
        if (!TYPES.contains(ref.type())) {
            throw new IllegalStateException(where + ": '" + ref.type() + "' is not a resource type of the contracts " + TYPES);
        }
    }

    private static boolean identifiers(Type type) {
        Class<?> raw = rawClass(type);
        if (isIdentifier(raw)) {
            return true;
        }
        if (raw.isArray()) {
            return isIdentifier(raw.getComponentType());
        }
        return Collection.class.isAssignableFrom(raw) && type instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> element && isIdentifier(element);
    }

    private static boolean isIdentifier(Class<?> type) {
        return type == String.class || type == Integer.class || type == Long.class || type == int.class
                || type == long.class;
    }

    /** How the platform filters a result: a record with a resource component, a list, or a container of them. */
    public sealed interface ResultShape permits None, Element, Many, Fields {
    }

    /** Nothing in the result is a resource. */
    public record None() implements ResultShape {
    }

    /** A record that is a resource itself ({@code idComponent} of {@code type}) and may hold more inside. */
    public record Element(String idComponent, String type, Class<?> resolver, Fields inside) implements ResultShape {
    }

    /** A list or array whose elements have the shape {@code element}. */
    public record Many(ResultShape element) implements ResultShape {
    }

    /** A record without a resource component of its own, whose fields hold shapes. */
    public record Fields(Map<String, ResultShape> fields) implements ResultShape {
    }

    private static ResultShape shape(Type type, String where, int depth) {
        if (depth > MAX_DEPTH) {
            return new None();
        }
        Class<?> raw = rawClass(type);
        if (raw.isArray()) {
            return many(shape(raw.getComponentType(), where, depth + 1));
        }
        if (Collection.class.isAssignableFrom(raw) && type instanceof ParameterizedType parameterized) {
            return many(shape(parameterized.getActualTypeArguments()[0], where, depth + 1));
        }
        if (!raw.isRecord()) {
            return new None();
        }
        Map<String, ResultShape> fields = new LinkedHashMap<>();
        String idComponent = null;
        ResourceRef idRef = null;
        for (RecordComponent component : raw.getRecordComponents()) {
            ResourceRef ref = component.getAnnotation(ResourceRef.class);
            if (ref != null) {
                String here = where + "." + component.getName();
                checkType(ref, "@Tool " + here);
                if (!isIdentifier(component.getType())) {
                    throw new IllegalStateException("@Tool " + here + ": @ResourceRef on a result component needs a String or "
                            + "an integer, not " + component.getGenericType().getTypeName());
                }
                if (idRef != null) {
                    throw new IllegalStateException("@Tool " + where + ": a record has one @ResourceRef component, not "
                            + "two (" + idComponent + ", " + component.getName() + ")");
                }
                idComponent = component.getName();
                idRef = ref;
                continue;
            }
            ResultShape inner = shape(component.getGenericType(), where + "." + component.getName(), depth + 1);
            if (!(inner instanceof None)) {
                fields.put(component.getName(), inner);
            }
        }
        Fields inside = new Fields(Map.copyOf(fields));
        if (idRef != null) {
            return new Element(idComponent, idRef.type(), idRef.resolver(), inside);
        }
        return fields.isEmpty() ? new None() : inside;
    }

    private static ResultShape many(ResultShape element) {
        return element instanceof None ? element : new Many(element);
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

    private static Set<String> contractTypes() {
        String resource = "/mcp-contracts/schemas/common.schema.json";
        try (java.io.InputStream in = ResourceRefs.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing: mcp-contracts must be on the classpath");
            }
            JsonNode values = JSON.readTree(in).path("$defs").path("ResourceType").path("enum");
            Set<String> types = new HashSet<>();
            values.forEach(value -> types.add(value.asString()));
            return Set.copyOf(types);
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
