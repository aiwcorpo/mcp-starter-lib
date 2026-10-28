package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;
import pl.aiwcorpo.mcp.platform.guard.RiskClassifiedTool;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolAction;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.observability.AuditLogger;
import pl.aiwcorpo.mcp.platform.policy.PolicyEngine;
import pl.aiwcorpo.mcp.platform.resource.QueryScope;
import pl.aiwcorpo.mcp.platform.resource.ResourceBinding;
import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolvers;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The vectors {@code resource-checks/} and {@code result-filter/} of mcp-contracts, run on {@link ResourcePolicy} with the
 * example policies of the contracts as the signed bundle: a vector added to the contracts is run here without a line of code.
 * The vectors that give a tool with {@code resourceRefs} and no arguments (the result filters) exercise the filter and the query
 * scope, not {@code authorizeCall}: the starter refuses such a call as {@code no_resource} on purpose (STATUS, deviations).
 */
class ContractVectorsOnResourcePolicyTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int RESOURCE_CHECKS = 4;
    private static final int RESULT_FILTERS = 8;

    private static TestGateway gateway;
    private static PolicyEngine engine;

    record Vector(String name, JsonNode data) {
        @Override
        public String toString() {
            return name;
        }
    }

    @BeforeAll
    static void start() throws Exception {
        gateway = TestGateway.start("mcp-jira");
        McpPlatformProperties props = new McpPlatformProperties();
        Map<String, String> properties = gateway.serverProperties();
        McpPlatformProperties.Policy policy = props.getPolicy();
        policy.getMain().setUrl(properties.get("aiwcorpo.mcp.platform.policy.main.url"));
        policy.getMain().setKeyId("test-policy-bundle");
        policy.getMain().setPublicKey(properties.get("aiwcorpo.mcp.platform.policy.main.public-key"));
        policy.setAllowTestKeys(true);
        policy.setStartupTimeout(Duration.ofSeconds(5));
        engine = PolicyEngine.create(policy, null, null);
    }

    @AfterAll
    static void stop() {
        engine.close();
        gateway.close();
    }

    static List<Vector> vectors(String directory) throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mcp-contracts/test-vectors/" + directory + "/*.json");
        List<Vector> vectors = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                vectors.add(new Vector(resource.getFilename(), JSON.readTree(in)));
            }
        }
        vectors.sort(Comparator.comparing(Vector::name));
        return vectors;
    }

    static Stream<Arguments> resourceChecks() throws IOException {
        return vectors("resource-checks").stream().map(Arguments::of);
    }

    static Stream<Arguments> resultFilters() throws IOException {
        return vectors("result-filter").stream().map(Arguments::of);
    }

    @Test
    void theWholeDirectoriesAreRead() throws IOException {
        assertThat(vectors("resource-checks")).hasSize(RESOURCE_CHECKS);
        assertThat(vectors("result-filter")).hasSize(RESULT_FILTERS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resourceChecks")
    void everyValueOfTheCallIsEvaluatedAsTheVectorSays(Vector vector) {
        JsonNode input = vector.data().get("input");
        Fixture fixture = new Fixture(input.get("tool"), vector.data().get("resources"));
        try (ResourceContext context = fixture.open(input)) {
            if (vector.data().at("/expected/allowed").asBoolean()) {
                fixture.policy.authorizeCall();
            } else {
                assertThatThrownBy(fixture.policy::authorizeCall).isInstanceOf(SanitizedToolException.class);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("resultFilters")
    void theResultIsFilteredAndTheQueryLimitedAsTheVectorSays(Vector vector) {
        JsonNode input = vector.data().get("input");
        Fixture fixture = new Fixture(input.get("tool"), vector.data().get("items"));
        try (ResourceContext context = fixture.open(input)) {
            List<JsonNode> items = new ArrayList<>();
            vector.data().get("items").forEach(items::add);

            List<String> kept = fixture.policy.retainAllowed(items, fixture::resource).stream()
                    .map(item -> item.get("id").asString()).toList();

            assertThat(kept).as("kept").containsExactlyElementsOf(stringList(vector.data().at("/expected/kept")));
            JsonNode constraints = vector.data().at("/expected/queryConstraints");
            for (String type : constraints.propertyNames()) {
                QueryScope scope = fixture.policy.queryScope(type);
                JsonNode expected = constraints.get(type);
                assertThat(scope.linkType()).as(type).isEqualTo(expected.get("virtualServerType").asString());
                assertThat(scope.linkIds().map(List::copyOf)).as(type + " link")
                        .isEqualTo(expected.get("virtualServer").isNull() ? Optional.empty()
                                : Optional.of(stringList(expected.get("virtualServer"))));
                assertThat(List.copyOf(scope.grantedIds())).as(type + " granted").isEqualTo(stringList(expected.get("granted")));
            }
        }
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(value -> values.add(value.asString()));
        return values;
    }

    /** A policy over the vector's tool, with resolvers that answer from the resources the vector lists. */
    private static final class Fixture {

        final String serverId;
        final String system;
        final String toolName;
        final ResourcePolicy policy;
        final Map<String, ResolvedResource> resources = new LinkedHashMap<>();

        Fixture(JsonNode tool, JsonNode listed) {
            serverId = tool.get("serverId").asString();
            system = tool.get("system").asString();
            toolName = tool.get("name").asString();
            listed.forEach(node -> {
                ResolvedResource resource = resourceOf(node);
                resources.put(resource.type() + "\u0000" + resource.id(), resource);
            });
            List<ResourceBinding> refs = new ArrayList<>();
            tool.get("resourceRefs").forEach(ref -> refs.add(new ResourceBinding(ref.get("argument").asString(),
                    ref.get("type").asString(), Void.class)));
            ToolRiskRegistry registry = new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL);
            registry.register(new VectorTool(toolName, RiskLevel.valueOf(tool.get("risk").asString()),
                    ToolAction.valueOf(tool.get("action").asString().toUpperCase(java.util.Locale.ROOT)), refs));
            List<ResourceResolver<?>> resolvers = new ArrayList<>();
            resources.values().stream().map(ResolvedResource::type).distinct().forEach(type -> resolvers.add(new Resolver(type)));
            policy = new ResourcePolicy(engine, new ResourceResolvers(resolvers), registry,
                    new AuditLogger("test.vectors.audit", serverId), serverId, system, 200);
        }

        ResolvedResource resource(JsonNode item) {
            return resourceOf(item);
        }

        ResourceContext open(JsonNode input) {
            JsonNode caller = input.get("caller");
            Map<String, List<String>> vsc = caller.has("vsc")
                    ? JSON.convertValue(caller.get("vsc"), new TypeReference<Map<String, List<String>>>() { }) : null;
            CallerContext claims = new CallerContext(caller.get("sub").asString(), caller.get("usr").asString(),
                    stringList(caller.get("grp")), caller.get("azp").asString(), caller.has("vs") ? caller.get("vs").asString() : null,
                    vsc, toolName, "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", "00000000000000-0000000", null);
            Map<String, Object> arguments = JSON.convertValue(input.get("arguments"), new TypeReference<Map<String, Object>>() { });
            return ResourceContext.open(claims, toolName, arguments);
        }

        private static ResolvedResource resourceOf(JsonNode node) {
            ResolvedResource resource = ResolvedResource.of(node.get("type").asString(), node.get("id").asString(),
                    attributes(node.get("attrs")));
            return node.has("parent") ? resource.withParent(resourceOf(node.get("parent"))) : resource;
        }

        private static Map<String, Object> attributes(JsonNode attrs) {
            return JSON.convertValue(attrs, new TypeReference<LinkedHashMap<String, Object>>() { });
        }

        private final class Resolver implements ResourceResolver<ResolvedResource> {

            private final String type;

            Resolver(String type) {
                this.type = type;
            }

            @Override
            public String resourceType() {
                return type;
            }

            @Override
            public Optional<ResolvedResource> fetch(String id) {
                return Optional.ofNullable(resources.get(type + "\u0000" + id));
            }

            @Override
            public ResolvedResource describe(ResolvedResource fetched) {
                return fetched;
            }
        }
    }

    private record VectorTool(String name, RiskLevel riskLevel, ToolAction action, List<ResourceBinding> resourceRefs)
            implements ToolCallback, RiskClassifiedTool {

        @Override
        public String title() {
            return "Vector tool";
        }

        @Override
        public boolean resultWithoutResources() {
            return true;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name(name).description("d")
                    .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}").build();
        }

        @Override
        public String call(String toolInput) {
            return "";
        }
    }
}
