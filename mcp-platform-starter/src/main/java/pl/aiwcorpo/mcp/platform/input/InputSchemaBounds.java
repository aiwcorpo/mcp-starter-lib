package pl.aiwcorpo.mcp.platform.input;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The startup rule behind {@link ToolInputPolicy}: every level of a tool's {@code inputSchema} has a limit, so
 * the model cannot hand the tool an unbounded amount of data at any depth.
 *
 * <ul>
 *   <li>object: {@code additionalProperties: false}, or a typed map with {@code maxProperties}, key length
 *       ({@code propertyNames.maxLength}) and a bounded value schema;</li>
 *   <li>string (except {@code enum}/{@code const}): {@code maxLength}; a {@code pattern} is anchored with
 *       {@code ^} and {@code $} and has no alternation outside a group, because an unanchored match
 *       ({@code [0-9]+} accepts {@code ../../1}, {@code ^a|b$} accepts {@code a/../x}) is no format at all;</li>
 *   <li>array: {@code maxItems} and an {@code items} schema that is bounded in turn (after {@code prefixItems}
 *       too: {@code items: false} or a bounded schema, so elements past the prefix have a limit);</li>
 *   <li>integer, number: a lower and an upper bound;</li>
 *   <li>no type at all (accepts any JSON value), {@code patternProperties} and the combinators
 *       ({@code allOf}, {@code anyOf}, {@code oneOf}, {@code not}, {@code if}) are refused.</li>
 * </ul>
 * Problems carry the path of declared property names (schema author text), never request data.
 */
final class InputSchemaBounds {

    private static final int MAX_DEPTH = 8;

    private final JsonNode root;
    private final List<String> problems = new ArrayList<>();

    private InputSchemaBounds(JsonNode root) {
        this.root = root;
    }

    static List<String> problems(JsonNode root) {
        InputSchemaBounds bounds = new InputSchemaBounds(root);
        bounds.visit(root, "", 0, new HashSet<>());
        return List.copyOf(bounds.problems);
    }

    private void visit(JsonNode schema, String path, int depth, Set<String> followedRefs) {
        String where = path.isEmpty() ? "the arguments" : path;
        if (depth > MAX_DEPTH) {
            problems.add(where + ": nested deeper than " + MAX_DEPTH + " levels");
            return;
        }
        if (!schema.isObject()) {
            problems.add(where + ": a boolean schema accepts any value");
            return;
        }
        String ref = schema.path("$ref").asString(null);
        if (ref != null) {
            JsonNode target = ref.startsWith("#") ? root.at(ref.substring(1)) : null;
            if (target == null || target.isMissingNode()) {
                problems.add(where + ": $ref '" + ref + "' cannot be resolved inside the schema");
            } else if (followedRefs.add(ref)) {
                visit(target, path, depth + 1, followedRefs);
                followedRefs.remove(ref);
            } else {
                problems.add(where + ": recursive $ref '" + ref + "' has no bound");
            }
            return;
        }
        if (schema.has("enum") || schema.has("const")) {
            return;
        }
        for (String keyword : List.of("allOf", "anyOf", "oneOf", "not", "if", "then", "else")) {
            if (schema.has(keyword)) {
                problems.add(where + ": " + keyword + " is not supported (a limit has to be readable at each level; "
                        + "write the level out)");
                return;
            }
        }
        List<String> types = types(schema);
        if (types.isEmpty()) {
            problems.add(where + ": has no type, so it accepts any JSON value");
            return;
        }
        for (String type : types) {
            switch (type) {
                case "object" -> object(schema, path, where, depth, followedRefs);
                case "array" -> array(schema, path, where, depth, followedRefs);
                case "string" -> string(schema, where);
                case "integer", "number" -> number(schema, where);
                default -> { }
            }
        }
    }

    private static List<String> types(JsonNode schema) {
        JsonNode type = schema.path("type");
        List<String> result = new ArrayList<>();
        if (type.isString()) {
            result.add(type.asString());
        } else if (type.isArray()) {
            type.forEach(t -> result.add(t.asString()));
        }
        return result;
    }

    private void object(JsonNode schema, String path, String where, int depth, Set<String> followedRefs) {
        if (schema.has("patternProperties")) {
            problems.add(where + ": patternProperties cannot be bounded");
        }
        JsonNode additional = schema.path("additionalProperties");
        if (additional.isBoolean() && !additional.asBoolean()) {
            // closed: only the declared properties
        } else if (additional.isObject()) {
            if (!schema.path("maxProperties").isIntegralNumber()) {
                problems.add(where + ": a map needs maxProperties");
            }
            if (!schema.path("propertyNames").path("maxLength").isIntegralNumber()) {
                problems.add(where + ": a map needs propertyNames.maxLength");
            }
            visit(additional, path + "{value}", depth + 1, followedRefs);
        } else {
            problems.add(where + ": accepts undeclared properties (additionalProperties: false is missing)");
        }
        JsonNode properties = schema.path("properties");
        for (String name : properties.propertyNames()) {
            visit(properties.get(name), path.isEmpty() ? name : path + "." + name, depth + 1, followedRefs);
        }
    }

    private void array(JsonNode schema, String path, String where, int depth, Set<String> followedRefs) {
        if (!schema.path("maxItems").isIntegralNumber()) {
            problems.add(where + ": a list needs maxItems");
        }
        JsonNode items = schema.path("items");
        JsonNode prefix = schema.path("prefixItems");
        if (items.isMissingNode()) {
            problems.add(where + ": a list needs an items schema (items: false after prefixItems)");
        } else if (!(items.isBoolean() && !items.asBoolean())) {
            visit(items, path + "[]", depth + 1, followedRefs);
        }
        if (prefix.isArray()) {
            prefix.forEach(element -> visit(element, path + "[]", depth + 1, followedRefs));
        }
    }

    private void string(JsonNode schema, String where) {
        if (!schema.path("maxLength").isIntegralNumber()) {
            problems.add(where + ": text needs maxLength");
        }
        String pattern = schema.path("pattern").asString(null);
        if (pattern != null && !anchored(pattern)) {
            problems.add(where + ": pattern '" + pattern + "' must start with ^, end with $ and keep any alternation "
                    + "inside a group, e.g. ^(?:a|b)$");
        }
    }

    private void number(JsonNode schema, String where) {
        boolean lower = schema.has("minimum") || schema.has("exclusiveMinimum");
        boolean upper = schema.has("maximum") || schema.has("exclusiveMaximum");
        if (!lower || !upper) {
            problems.add(where + ": a number needs minimum and maximum");
        }
    }

    /** {@code ^...$} (or {@code \z}) with no {@code |} outside parentheses and character classes. */
    static boolean anchored(String pattern) {
        if (!pattern.startsWith("^") || !(pattern.endsWith("$") || pattern.endsWith("\\z"))
                || (pattern.endsWith("$") && pattern.endsWith("\\$"))) {
            return false;
        }
        int depth = 0;
        boolean inClass = false;
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                i++;
            } else if (inClass) {
                inClass = c != ']';
            } else if (c == '[') {
                inClass = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == '|' && depth == 0) {
                return false;
            }
        }
        return true;
    }
}
