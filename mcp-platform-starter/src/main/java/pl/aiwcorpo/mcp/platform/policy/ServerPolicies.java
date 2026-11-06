package pl.aiwcorpo.mcp.platform.policy;

import com.cedarpolicy.model.policy.PolicySet;
import com.cedarpolicy.model.schema.Schema;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import pl.aiwcorpo.mcp.cedar.entities.PolicyFragments;
import pl.aiwcorpo.mcp.cedar.entities.ResourceTypeDeclaration;
import pl.aiwcorpo.mcp.cedar.entities.ResourceTypes;
import pl.aiwcorpo.mcp.manifest.PoliciesHash;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The policies a server brings in its own jar (C6, C7): its Cedar schema fragment, its resource policies and the declarations of
 * its resource types, read from {@code src/main/resources/mcp/policies/}:
 *
 * <ul>
 *   <li>{@code schema.cedarschema}: exactly one namespace, the server's own, with its entity types and the actions
 *       {@code read}, {@code update}, {@code delete}, {@code admin};</li>
 *   <li>{@code *.cedar}: the resource policies; every policy has an {@code @id} unique in the server;</li>
 *   <li>{@code resource-types.json}: the {@code resourceTypes} of the manifest, one declaration per entity type of the schema.</li>
 * </ul>
 *
 * <p>An instance is valid by construction: the fragment composes with the base schema of the contracts, validates in strict mode and
 * agrees with the declarations ({@link PolicyFragments#validateFragment}, the check the gateway repeats at registration). A server
 * whose policies do not pass does not start, and no manifest is generated for it.
 */
public final class ServerPolicies {

    /** Directory of the server's policies on its classpath. */
    public static final String DIRECTORY = "mcp/policies";
    public static final String SCHEMA_FILE = "schema.cedarschema";
    public static final String RESOURCE_TYPES_FILE = "resource-types.json";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern POLICY_FILE_NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}\\.cedar$");

    private final String namespace;
    private final String schemaText;
    private final Map<String, String> files;
    private final String resourceTypesJson;
    private final ResourceTypes resourceTypes;
    private final Schema schema;
    private final PolicySet policies;

    private ServerPolicies(String namespace, String schemaText, Map<String, String> files, String resourceTypesJson,
                           ResourceTypes resourceTypes) {
        this.namespace = namespace;
        this.schemaText = schemaText;
        this.files = Map.copyOf(files);
        this.resourceTypesJson = resourceTypesJson;
        this.resourceTypes = resourceTypes;
        this.schema = PolicyFragments.composeSchema(schemaText);
        this.policies = PolicyFragments.policySet(new TreeMap<>(files));
    }

    /**
     * The policies of the server on the classpath of {@code loader}.
     *
     * @throws IllegalStateException with the reason when a file is missing, malformed or fails the validation of the fragment
     */
    public static ServerPolicies load(ClassLoader loader) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(loader);
        Map<String, String> policyFiles = new TreeMap<>();
        Set<String> roots = new TreeSet<>();
        String schema = single(resolver, SCHEMA_FILE, roots);
        String types = single(resolver, RESOURCE_TYPES_FILE, roots);
        try {
            for (Resource resource : resolver.getResources("classpath*:" + DIRECTORY + "/*.cedar")) {
                String name = resource.getFilename();
                roots.add(root(resource, name));
                if (policyFiles.put(name, read(resource)) != null) {
                    throw new IllegalStateException(DIRECTORY + "/" + name + " exists twice on the classpath");
                }
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot list " + DIRECTORY, ex);
        }
        if (roots.size() > 1) {
            // The policies are the application's own. A library on the classpath that ships files under mcp/policies would otherwise
            // add or replace policies of the server without anyone having written them there.
            throw new IllegalStateException("mcp/policies files come from more than one place on the classpath " + roots + ": only the "
                    + "application's own code may hold them (src/main/resources/" + DIRECTORY + "/); remove the other, or the dependency that ships it");
        }
        return of(schema, policyFiles, types);
    }

    /**
     * The policies from their texts, for a test that holds them in memory.
     *
     * @param schemaText text of {@code schema.cedarschema}
     * @param policyFiles file name to text of the {@code *.cedar} files
     * @param resourceTypesJson the {@code resourceTypes} array
     * @throws IllegalStateException with every reason when the fragment is not valid
     */
    public static ServerPolicies of(String schemaText, Map<String, String> policyFiles, String resourceTypesJson) {
        ResourceTypes types;
        try {
            types = ResourceTypes.fromJson(resourceTypesJson);
        } catch (IllegalArgumentException ex) {
            throw invalid(List.of(RESOURCE_TYPES_FILE + ": " + ex.getMessage()));
        }
        if (types.declarations().isEmpty()) {
            throw invalid(List.of(RESOURCE_TYPES_FILE + " declares no resource type"));
        }
        TreeSet<String> namespaces = new TreeSet<>();
        for (ResourceTypeDeclaration declaration : types.declarations()) {
            namespaces.add(declaration.namespace());
        }
        if (namespaces.size() != 1) {
            throw invalid(List.of(RESOURCE_TYPES_FILE + ": all types must be in the one Cedar namespace of the server, found "
                    + namespaces));
        }
        List<String> problems = new java.util.ArrayList<>();
        policyFiles.keySet().forEach(name -> {
            if (!POLICY_FILE_NAME.matcher(name).matches()) {
                problems.add(DIRECTORY + "/" + name + ": a policy file name is lower case letters, digits, '.', '_' or '-' and ends in .cedar");
            }
        });
        String namespace = namespaces.first();
        if (problems.isEmpty()) {
            PolicyFragments.Result result;
            try {
                result = PolicyFragments.validateFragment(namespace, schemaText, new TreeMap<>(policyFiles), types);
            } catch (RuntimeException ex) {
                result = new PolicyFragments.Result(List.of(String.valueOf(ex.getMessage())));
            }
            problems.addAll(result.errors());
        }
        if (!problems.isEmpty()) {
            throw invalid(problems);
        }
        return new ServerPolicies(namespace, schemaText, policyFiles, resourceTypesJson, types);
    }

    private static IllegalStateException invalid(List<String> problems) {
        return new IllegalStateException("The policies of this server (src/main/resources/" + DIRECTORY + "/) are not valid, "
                + "the gateway would reject them:\n - " + String.join("\n - ", problems));
    }

    private static String single(PathMatchingResourcePatternResolver resolver, String file, Set<String> roots) {
        try {
            Resource[] found = resolver.getResources("classpath*:" + DIRECTORY + "/" + file);
            if (found.length == 0) {
                throw new IllegalStateException("src/main/resources/" + DIRECTORY + "/" + file + " is missing: a server built on the "
                        + "starter brings its own Cedar policies (schema.cedarschema, *.cedar and resource-types.json in "
                        + "src/main/resources/" + DIRECTORY + "/), see the README, 'Policies of the server'");
            }
            for (Resource resource : found) {
                roots.add(root(resource, file));
            }
            if (found.length > 1) {
                throw new IllegalStateException(DIRECTORY + "/" + file + " exists " + found.length + " times on the classpath: only the "
                        + "application's own code may hold it");
            }
            return read(found[0]);
        } catch (IOException ex) {
            throw new UncheckedIOException("cannot read " + DIRECTORY + "/" + file, ex);
        }
    }

    /** The classpath root (a jar or a directory) a policy file comes from. */
    private static String root(Resource resource, String file) throws IOException {
        String url = resource.getURL().toString();
        int at = url.lastIndexOf(DIRECTORY + "/" + file);
        return at < 0 ? url : url.substring(0, at);
    }

    private static String read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The Cedar namespace of the server, {@code policies.namespace} of the manifest. */
    public String namespace() {
        return namespace;
    }

    /** Text of {@code schema.cedarschema}. */
    public String schemaText() {
        return schemaText;
    }

    /** File name to text of the {@code *.cedar} files. */
    public Map<String, String> files() {
        return new TreeMap<>(files);
    }

    /** The declared resource types of the server. */
    public ResourceTypes resourceTypes() {
        return resourceTypes;
    }

    /** The {@code resourceTypes} array as written, for the manifest. */
    public String resourceTypesJson() {
        return resourceTypesJson;
    }

    /** The base schema of the contracts composed with the fragment: the schema every request of the server is checked against. */
    public Schema schema() {
        return schema;
    }

    /** The resource policies of the server, each with its {@code @id} as policy id. */
    public PolicySet policies() {
        return policies;
    }

    /** {@code policies} member of the manifest: namespace, schema and the files in file name order. */
    public ObjectNode manifestPolicies() {
        ObjectNode node = JSON.createObjectNode();
        node.put("namespace", namespace);
        node.put("schema", schemaText);
        ArrayNode list = node.putArray("files");
        new TreeMap<>(files).forEach((name, text) -> {
            ObjectNode file = list.addObject();
            file.put("name", name);
            file.put("text", text);
        });
        return node;
    }

    /** {@code policiesHash} of the manifest: covers the policies and the resource type declarations. */
    public String policiesHash() {
        return PoliciesHash.of(manifestPolicies().toString(), JSON.readTree(resourceTypesJson).toString());
    }
}
