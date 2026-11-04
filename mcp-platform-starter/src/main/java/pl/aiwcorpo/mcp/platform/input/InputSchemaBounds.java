package pl.aiwcorpo.mcp.platform.input;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * The startup rule behind {@link ToolInputPolicy}: every level of a tool's {@code inputSchema} has a limit, so
 * the model cannot hand the tool an unbounded amount of data at any depth.
 *
 * <ul>
 *   <li>object: {@code additionalProperties: false}; no map, typed or not, and no {@code patternProperties} or
 *       {@code unevaluatedProperties} (CC-078, CC-123);</li>
 *   <li>string (except {@code enum}/{@code const}): {@code maxLength}; a {@code pattern} is anchored with
 *       {@code ^} and {@code $} and has no alternation outside a group, because an unanchored match
 *       ({@code [0-9]+} accepts {@code ../../1}, {@code ^a|b$} accepts {@code a/../x}) is no format at all;</li>
 *   <li>array: {@code maxItems} and an {@code items} schema that is bounded in turn (after {@code prefixItems}
 *       too, so elements past the prefix have a limit);</li>
 *   <li>integer, number: {@code minimum} and {@code maximum};</li>
 *   <li>every node has exactly one {@code type} of string, integer, number, boolean, array or object (no union, no
 *       {@code null}, no {@code $ref}), and the combinators ({@code allOf}, {@code anyOf}, {@code oneOf},
 *       {@code not}, {@code if}, {@code then}, {@code else}) are refused.</li>
 * </ul>
 * Problems carry the path of declared property names (schema author text), never request data.
 *
 * <p>The rules are those of the manifest rules {@code input-schema-closed}, {@code input-schema-bounded} and
 * {@code input-schema-no-combinators} of mcp-manifest (README of mcp-contracts, "Manifest semantic rules"): a
 * schema this class accepts passes them, so the manifest the server publishes is accepted by the gateway.
 */
final class InputSchemaBounds {

    private static final int MAX_DEPTH = 8;
    private static final List<String> COMBINATORS = List.of("allOf", "anyOf", "oneOf", "not", "if", "then", "else");
    private static final Set<String> TYPES = Set.of("string", "integer", "number", "boolean", "array", "object");

    private final List<String> problems = new ArrayList<>();

    private InputSchemaBounds() {
    }

    static List<String> problems(JsonNode root) {
        InputSchemaBounds bounds = new InputSchemaBounds();
        if (!root.isObject() || !"object".equals(root.path("type").asString(null))) {
            bounds.problems.add("the arguments: the root of the schema must be type object");
            return List.copyOf(bounds.problems);
        }
        bounds.visit(root, "", 0);
        return List.copyOf(bounds.problems);
    }

    private void visit(JsonNode schema, String path, int depth) {
        String where = path.isEmpty() ? "the arguments" : path;
        if (depth > MAX_DEPTH) {
            problems.add(where + ": nested deeper than " + MAX_DEPTH + " levels");
            return;
        }
        if (!schema.isObject()) {
            problems.add(where + ": a boolean schema accepts any value");
            return;
        }
        for (String keyword : COMBINATORS) {
            if (schema.has(keyword)) {
                problems.add(where + ": " + keyword + " is not supported (a limit has to be readable at each level; "
                        + "write the level out)");
            }
        }
        if (schema.has("$ref")) {
            problems.add(where + ": $ref is not supported (every level is written out)");
            return;
        }
        JsonNode typeNode = schema.get("type");
        if (typeNode == null || !typeNode.isString() || !TYPES.contains(typeNode.asString())) {
            problems.add(where + ": needs exactly one type of string, integer, number, boolean, array or object");
            return;
        }
        boolean fixed = schema.has("enum") || schema.has("const");
        String pattern = schema.path("pattern").asString(null);
        if (schema.has("pattern") && (pattern == null || !anchored(pattern))) {
            problems.add(where + ": pattern must start with ^, end with $ and keep any alternation inside a group, "
                    + "e.g. ^(?:a|b)$");
        }
        switch (typeNode.asString()) {
            case "object" -> object(schema, path, where, depth);
            case "array" -> array(schema, path, where, depth);
            case "string" -> {
                if (!fixed && !count(schema.get("maxLength"))) {
                    problems.add(where + ": text needs maxLength");
                }
            }
            case "integer", "number" -> {
                if (!fixed && !(schema.path("minimum").isNumber() && schema.path("maximum").isNumber())) {
                    problems.add(where + ": a number needs minimum and maximum");
                }
            }
            default -> { }
        }
    }

    private void object(JsonNode schema, String path, String where, int depth) {
        if (schema.has("patternProperties") || schema.has("unevaluatedProperties")) {
            problems.add(where + ": patternProperties and unevaluatedProperties cannot be bounded");
        }
        JsonNode additional = schema.path("additionalProperties");
        if (!additional.isBoolean() || additional.asBoolean()) {
            problems.add(where + ": accepts undeclared properties (additionalProperties: false is missing); a map "
                    + "cannot be bounded, use a record or a list of records");
        }
        JsonNode properties = schema.path("properties");
        for (String name : properties.propertyNames()) {
            visit(properties.get(name), path.isEmpty() ? name : path + "." + name, depth + 1);
        }
    }

    private void array(JsonNode schema, String path, String where, int depth) {
        if (!count(schema.get("maxItems"))) {
            problems.add(where + ": a list needs maxItems");
        }
        JsonNode items = schema.get("items");
        if (items == null) {
            problems.add(where + ": a list needs an items schema");
        } else {
            visit(items, path + "[]", depth + 1);
        }
        JsonNode prefix = schema.path("prefixItems");
        if (prefix.isArray()) {
            prefix.forEach(element -> visit(element, path + "[]", depth + 1));
        }
    }

    private static boolean count(JsonNode value) {
        return value != null && value.isIntegralNumber() && value.asLong() >= 0;
    }

    /** {@code ^...$} with the last {@code $} not escaped and no {@code |} outside parentheses and character classes. */
    static boolean anchored(String pattern) {
        if (!pattern.startsWith("^") || !pattern.endsWith("$")) {
            return false;
        }
        int backslashes = 0;
        for (int i = pattern.length() - 2; i >= 0 && pattern.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        if (backslashes % 2 == 1) {
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
