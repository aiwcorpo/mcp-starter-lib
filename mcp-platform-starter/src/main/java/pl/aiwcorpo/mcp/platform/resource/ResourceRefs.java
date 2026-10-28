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
import java.util.Optional;
import java.util.Set;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
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
     * @throws IllegalStateException for a type outside the contracts, a parameter that cannot carry identifiers, two
     *         resolvers for one type in one tool, or a {@link ResourceRef} on a component of an argument record (it would
     *         be ignored, and the tool would run without the check)
     */
    public static List<ResourceBinding> arguments(Method method) {
        List<ResourceBinding> bindings = new ArrayList<>();
        Map<String, Class<?>> resolverByType = new java.util.HashMap<>();
        for (Parameter parameter : method.getParameters()) {
            if (ToolContext.class.isAssignableFrom(parameter.getType())) {
                continue;
            }
            String where = "@Tool " + method.getName() + "(" + parameter.getName() + ")";
            nestedRefs(ResolvableType.forMethodParameter(method, java.util.Arrays.asList(method.getParameters()).indexOf(parameter)),
                    where, 0);
            ResourceRef ref = parameter.getAnnotation(ResourceRef.class);
            if (ref == null) {
                continue;
            }
            checkType(ref, where);
            if (!identifiers(parameter.getParameterizedType())) {
                throw new IllegalStateException(where + ": @ResourceRef needs a String, an integer, or a list or array of them, not "
                        + parameter.getParameterizedType().getTypeName());
            }
            Class<?> previous = resolverByType.putIfAbsent(ref.type(), ref.resolver());
            if (previous != null && previous != ref.resolver()) {
                throw new IllegalStateException("@Tool " + method.getName() + ": two arguments of type " + ref.type()
                        + " name different resolvers (" + previous.getSimpleName() + ", " + ref.resolver().getSimpleName()
                        + "); a call resolves one type with one resolver");
            }
            bindings.add(new ResourceBinding(parameter.getName(), ref.type(), ref.resolver()));
        }
        return List.copyOf(bindings);
    }

    /**
     * A {@link ResourceRef} below the top level of an argument is never read; the tool would run unchecked. Followed:
     * arrays, collections, {@code Optional}, the values of a {@code Map}, records, and the fields of a class that is not a
     * record (its fields are where a Jackson-bound object would carry an annotated record). A type that cannot be followed
     * ({@code Object}, an interface, a type variable) carries no annotation the platform could see, so there is nothing to
     * ignore silently.
     */
    private static void nestedRefs(ResolvableType type, String where, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException(where + ": the argument type is nested deeper than " + MAX_DEPTH + " levels, so the "
                    + "platform cannot tell whether it holds a @ResourceRef (which would be ignored). Flatten the argument.");
        }
        Class<?> raw = type.resolve(Object.class);
        if (isScalar(raw)) {
            return;
        }
        if (raw.isArray()) {
            nestedRefs(type.getComponentType(), where + "[]", depth + 1);
        } else if (Optional.class.isAssignableFrom(raw)) {
            nestedRefs(type.getGeneric(0), where, depth + 1);
        } else if (Collection.class.isAssignableFrom(raw)) {
            nestedRefs(type.asCollection().getGeneric(0), where + "[]", depth + 1);
        } else if (Map.class.isAssignableFrom(raw)) {
            nestedRefs(type.asMap().getGeneric(1), where + "{}", depth + 1);
        } else if (raw.isRecord()) {
            for (RecordComponent component : raw.getRecordComponents()) {
                if (component.getAnnotation(ResourceRef.class) != null) {
                    throw nestedRef(where + "." + component.getName());
                }
                nestedRefs(ResolvableType.forType(component.getGenericType(), type), where + "." + component.getName(), depth + 1);
            }
        } else if (raw.isSealed() && raw.getPermittedSubclasses() != null) {
            // Polymorphic arguments (Jackson subtypes): every permitted subtype is a possible argument.
            for (Class<?> permitted : raw.getPermittedSubclasses()) {
                nestedRefs(ResolvableType.forClass(permitted), where + "<" + permitted.getSimpleName() + ">", depth + 1);
            }
        } else if (raw != Object.class && !raw.getName().startsWith("java.")) {
            for (Class<?> level = raw; level != null && level != Object.class; level = level.getSuperclass()) {
                for (java.lang.reflect.Field field : level.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    if (field.getAnnotation(ResourceRef.class) != null) {
                        throw nestedRef(where + "." + field.getName());
                    }
                    nestedRefs(ResolvableType.forField(field, type), where + "." + field.getName(), depth + 1);
                }
            }
        }
    }

    private static IllegalStateException nestedRef(String where) {
        return new IllegalStateException(where + ": @ResourceRef is read on a parameter of the @Tool method, not on a component "
                + "of an argument record, where it would be ignored and the tool would run without the policy check. Pass the "
                + "identifier as a parameter of its own.");
    }

    /**
     * The resource components of the result type of {@code method}, as the shape the platform filters.
     *
     * <p>Fail-closed: a result whose type holds resource components must be a shape the platform can follow (records,
     * also generic ones, in lists, arrays, sets and {@code Optional}), without Jackson annotations that rename or move
     * fields. A result with a part the platform cannot see into (a {@code Map}, a class that is not a record,
     * {@code Object}, {@code JsonNode}, a {@code Stream}) stops the server unless the method is marked
     * {@link ResultWithoutResources}.
     *
     * @throws IllegalStateException for such a result
     */
    public static ResultShape result(Method method) {
        Walk walk = new Walk();
        ResultShape shape = walk.shape(ResolvableType.forMethodReturnType(method), "result", 0, true);
        String where = "@Tool " + method.getName();
        if (!walk.unsupported.isEmpty()) {
            throw new IllegalStateException(where + ": the result cannot be filtered by the platform: " + walk.unsupported.get(0)
                    + ". Return records (also generic ones) in lists, arrays and Optional, without Jackson annotations on them.");
        }
        boolean declared = AnnotatedElementUtils.hasAnnotation(method, ResultWithoutResources.class);
        if (declared && !(shape instanceof None)) {
            throw new IllegalStateException(where + ": marked @ResultWithoutResources but the result type holds a @ResourceRef "
                    + "component; remove one of them");
        }
        if (!declared && !walk.opaque.isEmpty()) {
            throw new IllegalStateException(where + ": the platform cannot see into the result (" + walk.opaque.get(0)
                    + "), so it cannot tell whether it holds resources it must filter. Return records with @ResourceRef "
                    + "components, or mark the method @ResultWithoutResources if the result holds no resources or the tool "
                    + "filters them itself with ResourcePolicy#retainAllowed.");
        }
        return shape;
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

    /**
     * A record without a resource component of its own, whose fields hold shapes; {@code known} is every component name,
     * so a field the platform did not expect in the JSON (a renaming the platform cannot see) fails the call.
     */
    public record Fields(Map<String, ResultShape> fields, Set<String> known) implements ResultShape {
    }

    private static final class Walk {

        final List<String> opaque = new ArrayList<>();
        final List<String> unsupported = new ArrayList<>();

        ResultShape shape(ResolvableType type, String where, int depth, boolean top) {
            if (depth > MAX_DEPTH) {
                opaque.add(where + " is nested deeper than " + MAX_DEPTH + " levels");
                return new None();
            }
            Class<?> raw = type.resolve(Object.class);
            if (top && (CharSequence.class.isAssignableFrom(raw) || raw == byte[].class)) {
                // Text or bytes as the result itself may be a finished JSON (a search response passed on as it came):
                // the platform cannot see the resources in it, so the tool says it holds none or filters them itself.
                opaque.add(where + " is " + (raw == byte[].class ? "a byte array" : "text") + " (it may be a ready JSON)");
                return new None();
            }
            if (raw == void.class || raw == Void.class || isScalar(raw)) {
                return new None();
            }
            if (Optional.class.isAssignableFrom(raw)) {
                return shape(type.getGeneric(0), where, depth + 1, top);
            }
            if (raw.isArray()) {
                return many(shape(type.getComponentType(), where + "[]", depth + 1, top));
            }
            if (Collection.class.isAssignableFrom(raw)) {
                return many(shape(type.asCollection().getGeneric(0), where + "[]", depth + 1, top));
            }
            if (Map.class.isAssignableFrom(raw)) {
                ResultShape inner = shape(type.asMap().getGeneric(1), where + "{}", depth + 1, false);
                if (!(inner instanceof None) || containsOpaque(type.asMap().getGeneric(1))
                        || !isScalar(type.asMap().getGeneric(0).resolve(Object.class))) {
                    opaque.add(where + " is a Map (its keys and values are not followed)");
                }
                return new None();
            }
            if (!raw.isRecord()) {
                opaque.add(where + " is " + raw.getName() + ", which is not a record");
                return new None();
            }
            Map<String, ResultShape> fields = new LinkedHashMap<>();
            Set<String> known = new java.util.LinkedHashSet<>();
            String idComponent = null;
            ResourceRef idRef = null;
            for (RecordComponent component : raw.getRecordComponents()) {
                known.add(component.getName());
                ResourceRef ref = component.getAnnotation(ResourceRef.class);
                if (ref != null) {
                    String here = "@Tool " + where + "." + component.getName();
                    checkType(ref, here);
                    if (!isIdentifier(component.getType())) {
                        throw new IllegalStateException(here + ": @ResourceRef on a result component needs a String or "
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
                ResultShape inner = shape(ResolvableType.forType(component.getGenericType(), type),
                        where + "." + component.getName(), depth + 1, false);
                if (!(inner instanceof None)) {
                    fields.put(component.getName(), inner);
                }
            }
            boolean holds = idRef != null || !fields.isEmpty();
            if (holds) {
                jacksonAnnotations(raw, where);
            }
            Fields inside = new Fields(Map.copyOf(fields), Set.copyOf(known));
            if (idRef != null) {
                return new Element(idComponent, idRef.type(), idRef.resolver(), inside);
            }
            return fields.isEmpty() ? new None() : inside;
        }

        private void jacksonAnnotations(Class<?> record, String where) {
            List<java.lang.annotation.Annotation> found = new ArrayList<>(List.of(record.getAnnotations()));
            for (RecordComponent component : record.getRecordComponents()) {
                found.addAll(List.of(component.getAnnotations()));
                found.addAll(List.of(component.getAccessor().getAnnotations()));
            }
            for (java.lang.annotation.Annotation annotation : found) {
                String name = annotation.annotationType().getName();
                if (name.startsWith("com.fasterxml.jackson.") || name.startsWith("tools.jackson.")) {
                    unsupported.add(where + " (" + record.getSimpleName() + ") has @" + annotation.annotationType().getSimpleName()
                            + ": the platform reads the JSON by the names of the record components, so renaming, unwrapping "
                            + "or ignoring them would hide resources from the filter");
                }
            }
        }

        private boolean containsOpaque(ResolvableType type) {
            Class<?> raw = type.resolve(Object.class);
            return !isScalar(raw) && !raw.isRecord() && !Collection.class.isAssignableFrom(raw) && !raw.isArray();
        }
    }

    private static boolean isScalar(Class<?> raw) {
        return raw.isPrimitive() || raw == String.class || raw == Character.class || raw == Boolean.class
                || Number.class.isAssignableFrom(raw) || raw.isEnum() || java.time.temporal.Temporal.class.isAssignableFrom(raw)
                || raw == java.util.Date.class || raw == java.util.UUID.class || raw == java.net.URI.class;
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
