package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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
import pl.aiwcorpo.mcp.platform.resource.ResourceBinding;
import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolvers;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The vectors of {@code test-vectors/servicedesk-service-account} (CC-076, CC-163) on {@code ResourcePolicy.authorizeCall}: the queue and ticket
 * of the vector are what the system returns, the grants are the queue groups of the caller in the bundle of the contracts, and the server
 * accepts the credential type of the vector. A service account reads only inside granted queues, and a user credential is not restricted by grants.
 */
class ServiceDeskVectorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
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
        gateway = TestGateway.start("mcp-servicedesk");
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

    static List<Vector> vectors() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mcp-contracts/test-vectors/servicedesk-service-account/*.json");
        List<Vector> vectors = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                vectors.add(new Vector(resource.getFilename(), JSON.readTree(in)));
            }
        }
        vectors.sort(Comparator.comparing(Vector::name));
        return vectors;
    }

    static Stream<Arguments> allVectors() throws IOException {
        return vectors().stream().map(Arguments::of);
    }

    @Test
    void theWholeDirectoryIsRead() throws IOException {
        assertThat(vectors()).hasSize(6);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVectors")
    void authorizeCallDecidesAsTheVectorSays(Vector vector) {
        JsonNode input = vector.data().get("input");
        JsonNode resource = input.get("resource");
        String type = resource.get("type").asString();
        String id = resource.get("id").asString();
        String queue = resource.get("queueReturnedBySystem").asString();
        List<String> groups = new ArrayList<>(List.of("mcp-servicedesk-read"));
        input.get("bundleQueueGrants").forEach(grant -> groups.add("mcp-servicedesk-queue-" + grant.asString()));
        List<String> asked = new ArrayList<>();
        ResolvedResource system = "ServiceDesk::Ticket".equals(type)
                ? ResolvedResource.of(type, id, Map.of("id", id)).withParent(ResolvedResource.of("ServiceDesk::Queue", queue, Map.of("id", queue)))
                : ResolvedResource.of(type, id, Map.of("id", id));
        ResourceResolver<ResolvedResource> resolver = new ResourceResolver<>() {
            @Override
            public String resourceType() {
                return type;
            }

            @Override
            public Optional<ResolvedResource> fetch(String requested) {
                asked.add(requested);
                return requested.equals(id) ? Optional.of(system) : Optional.empty();
            }

            @Override
            public ResolvedResource describe(ResolvedResource fetched) {
                return fetched;
            }
        };
        String argument = "ServiceDesk::Ticket".equals(type) ? "ticketId" : "queueId";
        ToolRiskRegistry registry = new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL);
        registry.register(new RefTool("get_thing", argument, type));
        ResourcePolicy policy = new ResourcePolicy(engine, new ResourceResolvers(List.of(resolver)), registry,
                new AuditLogger("test.servicedesk.vectors", "mcp-servicedesk"), "mcp-servicedesk", "servicedesk",
                List.of(input.get("credentialType").asString()), 200);
        CallerContext claims = new CallerContext("6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11", "sdreader", groups, "claude-code", null, null,
                "get_thing", "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", "00000000000000-0000000", null);

        try (ResourceContext context = ResourceContext.open(claims, "get_thing", Map.of(argument, id))) {
            if ("continue".equals(vector.data().at("/expected/decision").asString())) {
                policy.authorizeCall();
            } else {
                assertThatThrownBy(policy::authorizeCall).isInstanceOf(SanitizedToolException.class)
                        .extracting(error -> ((SanitizedToolException) error).getCode())
                        .isEqualTo(vector.data().at("/expected/errorCode").asString());
            }
        }
        if (vector.name().contains("no-queue-grant") || vector.name().contains("server-config-is-not-a-grant")) {
            assertThat(asked).as("the system is not asked for the ticket (invariant 6)").isEmpty();
        }
    }
}
