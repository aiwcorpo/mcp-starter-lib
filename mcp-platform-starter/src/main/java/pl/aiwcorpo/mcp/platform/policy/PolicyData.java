package pl.aiwcorpo.mcp.platform.policy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The administrator data of the gateway a server evaluates with: the answer of {@code GET /internal/v1/policy-data} (C7). The
 * shape is checked here; what the scope mapping and the overrides mean for this server is checked when a {@link PolicySnapshot} is
 * made from them.
 *
 * @param revision       a counter the gateway raises on every change, the {@code prv} it puts in its tokens; at least 1 (0 means no data)
 * @param approvedPoliciesHash the {@code policiesHash} (policies and resource types) the administrator approved for this server; empty while
 *                       nothing is approved, and then the server refuses every call
 * @param scopeMapping   the {@code scopeMapping} array as JSON text: {@code [{"group": "...", "scopes": ["<Type>/<id>"]}]}
 * @param allowedClients the AI clients ({@code azp}) that may use the platform; checked in code before any policy
 * @param overrides      the emergency {@code forbid} policies that target this server or all servers
 */
public record PolicyData(long revision, Optional<String> approvedPoliciesHash, String scopeMapping, List<String> allowedClients,
                         List<Override> overrides) {

    /** One emergency override: its id and the Cedar text of its single {@code forbid} policy. */
    public record Override(String id, String text) {
    }

    /** The largest integer a JSON number carries exactly, the bound of {@code PolicyRevision}. */
    private static final long MAX_REVISION = 9007199254740991L;
    private static final java.util.regex.Pattern HASH = java.util.regex.Pattern.compile("^sha256:[0-9a-f]{64}$");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public PolicyData {
        allowedClients = List.copyOf(allowedClients);
        overrides = List.copyOf(overrides);
    }

    /**
     * @param scopesByGroup group name to the scope ids it grants
     */
    public static PolicyData of(long revision, Optional<String> approvedPoliciesHash, Map<String, List<String>> scopesByGroup,
                                List<String> allowedClients, List<Override> overrides) {
        ArrayNode mapping = JSON.createArrayNode();
        scopesByGroup.forEach((group, scopes) -> {
            ObjectNode entry = mapping.addObject();
            entry.put("group", group);
            ArrayNode list = entry.putArray("scopes");
            scopes.forEach(list::add);
        });
        return new PolicyData(revision, approvedPoliciesHash, mapping.toString(), allowedClients, overrides);
    }

    /**
     * @throws IllegalArgumentException when the body is not exactly one JSON document of the documented shape
     */
    public static PolicyData parse(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("the policy data is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("the policy data is not a JSON object");
        }
        JsonNode revisionNode = root.path("revision");
        if (!revisionNode.isIntegralNumber() || !revisionNode.canConvertToLong() || revisionNode.asLong() < 1
                || revisionNode.asLong() > MAX_REVISION) {
            throw new IllegalArgumentException("the policy data has no valid revision (an integer of at least 1)");
        }
        long revision = revisionNode.asLong();
        Optional<String> approved = Optional.empty();
        if (root.has("approvedPoliciesHash")) {
            JsonNode hash = root.get("approvedPoliciesHash");
            if (!hash.isString() || !HASH.matcher(hash.asString()).matches()) {
                throw new IllegalArgumentException("approvedPoliciesHash is not a sha256 hash");
            }
            approved = Optional.of(hash.asString());
        }
        JsonNode mapping = root.path("scopeMapping");
        if (!mapping.isArray()) {
            throw new IllegalArgumentException("scopeMapping is not an array");
        }
        Set<String> clients = new LinkedHashSet<>();
        JsonNode allowed = root.path("allowedClients");
        if (!allowed.isArray()) {
            throw new IllegalArgumentException("allowedClients is not an array");
        }
        for (JsonNode client : allowed) {
            if (!client.isString() || client.asString().isBlank()) {
                throw new IllegalArgumentException("allowedClients holds something other than a client name");
            }
            clients.add(client.asString());
        }
        List<Override> overrides = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        JsonNode list = root.path("overrides");
        if (!list.isArray()) {
            throw new IllegalArgumentException("overrides is not an array");
        }
        for (JsonNode node : list) {
            String id = node.path("id").isString() ? node.path("id").asString() : null;
            String text = node.path("text").isString() ? node.path("text").asString() : null;
            if (id == null || id.isBlank() || text == null || !seen.add(id)) {
                throw new IllegalArgumentException("an override has no id or no text, or its id is used twice");
            }
            overrides.add(new Override(id, text));
        }
        return new PolicyData(revision, approved, mapping.toString(), List.copyOf(clients), overrides);
    }
}
