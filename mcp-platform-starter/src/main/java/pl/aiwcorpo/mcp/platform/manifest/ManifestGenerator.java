package pl.aiwcorpo.mcp.platform.manifest;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pl.aiwcorpo.mcp.manifest.DefinitionHash;
import pl.aiwcorpo.mcp.manifest.ManifestRules;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.resource.ResourceBinding;
import pl.aiwcorpo.mcp.platform.resource.ResourceRefs;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The C6 manifest of this server, built from what the server really publishes: the tool definitions of
 * {@code tools/list} (so the {@code definitionHash} the gateway pins is the hash of exactly what a client is shown), the
 * risk, action and {@code resourceRefs} the platform registered from the annotations, and the configuration in
 * {@code aiwcorpo.mcp.platform.manifest}. Before it is served it is checked against {@code server-manifest.schema.json}
 * and the semantic rules of mcp-manifest, the checks the gateway makes at registration; a manifest that fails them
 * stops the server, so a deployment never publishes a manifest the gateway would reject.
 */
public final class ManifestGenerator {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ManifestGenerator.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Schema MANIFEST_SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(SchemaLocation.of("classpath:mcp-contracts/schemas/server-manifest.schema.json"));

    /** Attributes the resolvers fill and the parent of a type: the entity model of {@code mcp.cedarschema}. */
    private static final Map<String, List<String>> ATTRIBUTES = Map.of(
            "Jira::Project", List.of("key", "category"),
            "Jira::Issue", List.of("key", "project"),
            "Confluence::Space", List.of("key", "labels"),
            "Confluence::Page", List.of("id", "space", "ancestorIds"),
            "GitLab::Group", List.of("path", "classification", "ancestorClassifications"),
            "GitLab::Project", List.of("path", "group", "classification", "ancestorClassifications"),
            "OpenSearch::Index", List.of("name", "tags"),
            "ServiceDesk::Queue", List.of("id", "classification"),
            "ServiceDesk::Ticket", List.of("id", "queue"));
    private static final Map<String, String> PARENTS = Map.of(
            "Jira::Issue", "Jira::Project",
            "Confluence::Page", "Confluence::Space",
            "GitLab::Project", "GitLab::Group",
            "ServiceDesk::Ticket", "ServiceDesk::Queue");

    private ManifestGenerator() {
    }

    /**
     * @param published the tools as the MCP server lists them
     * @throws IllegalStateException when the manifest is not one the gateway would accept
     */
    public static String generate(McpPlatformProperties props, ToolRiskRegistry registry, List<McpSchema.Tool> published,
                                  boolean strict) {
        return generate(props, registry, published, strict, false);
    }

    /**
     * @param devEnvironment true for a server of the {@code devstack} profile: the manifest says {@code "environment": "dev"} (CC-138), which a
     *                       gateway in production mode rejects ({@code environment_mismatch}). Otherwise the field is left out, which means production.
     */
    public static String generate(McpPlatformProperties props, ToolRiskRegistry registry, List<McpSchema.Tool> published,
                                  boolean strict, boolean devEnvironment) {
        ObjectNode manifest = JSON.createObjectNode();
        manifest.put("serverId", props.getServerId());
        manifest.put("version", props.getManifest().getVersion());
        manifest.put("system", props.system());
        manifest.put("internalUrl", props.getManifest().getInternalUrl());
        if (devEnvironment) {
            manifest.put("environment", "dev");
        }
        ObjectNode credential = manifest.putObject("credential");
        credential.put("required", props.getManifest().isCredentialRequired());
        ArrayNode accepted = credential.putArray("acceptedTypes");
        props.getManifest().getAcceptedCredentialTypes().forEach(accepted::add);

        Set<String> types = new LinkedHashSet<>();
        ArrayNode tools = JSON.createArrayNode();
        List<McpSchema.Tool> sorted = new ArrayList<>(published);
        sorted.sort((a, b) -> a.name().compareTo(b.name()));
        for (McpSchema.Tool tool : sorted) {
            tools.add(entry(props, registry, tool, types));
        }
        ArrayNode resourceTypes = manifest.putArray("resourceTypes");
        Set<String> declared = new LinkedHashSet<>();
        types.forEach(type -> declare(type, declared));
        declared.forEach(type -> {
            ObjectNode node = resourceTypes.addObject();
            node.put("type", type);
            ArrayNode attributes = node.putArray("attributes");
            ATTRIBUTES.get(type).forEach(attributes::add);
            if (PARENTS.containsKey(type)) {
                node.put("parent", PARENTS.get(type));
            }
        });
        manifest.set("tools", tools);

        String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);
        check(text, strict);
        return text;
    }

    private static void declare(String type, Set<String> declared) {
        if (declared.add(type) && PARENTS.containsKey(type)) {
            declare(PARENTS.get(type), declared);
        }
    }

    private static ObjectNode entry(McpPlatformProperties props, ToolRiskRegistry registry, McpSchema.Tool tool,
                                    Set<String> types) {
        String name = tool.name();
        RiskLevel risk = registry.riskOf(name).orElseThrow(() -> new IllegalStateException(
                "tool '" + name + "' is listed but was never registered with a risk"));
        String title = registry.titleOf(name).orElseThrow();
        if (!title.equals(tool.title())) {
            throw new IllegalStateException("tool '" + name + "': the published title differs from @ToolTitle");
        }
        McpSchema.ToolAnnotations annotations = tool.annotations();
        if (annotations == null || !Boolean.valueOf(risk == RiskLevel.READ_ONLY).equals(annotations.readOnlyHint())
                || !Boolean.valueOf(risk == RiskLevel.DESTRUCTIVE).equals(annotations.destructiveHint())) {
            throw new IllegalStateException("tool '" + name + "': readOnlyHint/destructiveHint of tools/list do not match "
                    + "the risk " + risk.name() + " (a DESTRUCTIVE tool must be published as destructive)");
        }
        ObjectNode entry = JSON.createObjectNode();
        entry.put("name", name);
        entry.put("risk", risk.name());
        entry.put("action", registry.actionOf(name).orElseThrow().wireName());
        ArrayNode refs = entry.putArray("resourceRefs");
        for (ResourceBinding binding : registry.resourceRefsOf(name)) {
            ObjectNode ref = refs.addObject();
            ref.put("argument", binding.argument());
            ref.put("type", binding.type());
            types.add(binding.type());
        }
        resultTypes(registry.resultShapeOf(name), types);
        JsonNode definition = JSON.valueToTree(tool);
        entry.set("definition", definition);
        entry.put("definitionHash", DefinitionHash.compute(definition.toString(), entry.toString(), props.system(),
                credentialOf(props).toString()));
        return entry;
    }

    private static ObjectNode credentialOf(McpPlatformProperties props) {
        ObjectNode credential = JSON.createObjectNode();
        credential.put("required", props.getManifest().isCredentialRequired());
        ArrayNode accepted = credential.putArray("acceptedTypes");
        props.getManifest().getAcceptedCredentialTypes().forEach(accepted::add);
        return credential;
    }

    private static void resultTypes(ResourceRefs.ResultShape shape, Set<String> types) {
        switch (shape) {
            case ResourceRefs.Element element -> {
                types.add(element.type());
                resultTypes(element.inside(), types);
            }
            case ResourceRefs.Many many -> resultTypes(many.element(), types);
            case ResourceRefs.Fields fields -> fields.fields().values().forEach(inner -> resultTypes(inner, types));
            case ResourceRefs.None none -> { }
        }
    }

    /** The checks of the gateway at registration: the JSON Schema of C6, then the semantic rules. */
    public static void check(String manifestJson, boolean strict) {
        List<Error> errors = MANIFEST_SCHEMA.validate(JSON.readTree(manifestJson));
        List<String> violations = ManifestRules.violations(manifestJson);
        if (errors.isEmpty() && violations.isEmpty()) {
            return;
        }
        String problem = errors.isEmpty()
                ? "The manifest of this server violates the manifest rules " + violations
                        + " (README of mcp-contracts, 'Reguły semantyczne manifestu'): the gateway would reject it"
                : "The manifest of this server does not match server-manifest.schema.json: "
                        + errors.stream().map(Error::toString).limit(5).toList();
        if (strict) {
            throw new IllegalStateException(problem);
        }
        LOG.warn("event=manifest_rejected_by_rules detail=\"{}\" profile=dev", problem.replace('"', '\''));
    }

    /** For tests: the rules over an existing manifest. */
    public static Map<String, Object> describe(String manifestJson) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("violations", ManifestRules.violations(manifestJson));
        return result;
    }
}
