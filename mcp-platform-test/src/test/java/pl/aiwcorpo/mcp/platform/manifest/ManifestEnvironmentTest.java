package pl.aiwcorpo.mcp.platform.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.modelcontextprotocol.server.McpSyncServer;
import pl.aiwcorpo.mcp.manifest.ManifestRules;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import pl.aiwcorpo.mcp.platform.test.resource.JiraServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code environment} of the C6 manifest (CC-138): a server of the {@code devstack} profile says {@code dev}, which a gateway in
 * production mode rejects; every other server leaves the field out, which means production.
 */
@SpringBootTest(classes = JiraServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ManifestEnvironmentTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    @AfterAll
    static void stopGateway() {
        GATEWAY.close();
    }

    @Autowired
    ObjectProvider<McpSyncServer> servers;

    @Autowired
    McpPlatformProperties props;

    @Autowired
    ToolRiskRegistry registry;

    private JsonNode manifest(String profile) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        ManifestCheck check = new PlatformManifestAutoConfiguration().manifestCheck(servers, props, registry, environment);
        check.afterSingletonsInstantiated();
        return JSON.readTree(check.manifest().orElseThrow());
    }

    @Test
    void theDevstackProfileMarksTheManifestAsDevAndItStillPassesTheRulesOfTheGateway() {
        JsonNode manifest = manifest("devstack");

        assertThat(manifest.get("environment").asString()).isEqualTo("dev");
        ManifestGenerator.check(manifest.toString(), true);
        assertThat(ManifestRules.violations(manifest.toString())).isEmpty();
    }

    @Test
    void anyOtherProfileLeavesTheFieldOutWhichMeansProduction() {
        assertThat(manifest("prod").has("environment")).isFalse();
        assertThat(manifest("dev").has("environment")).isFalse();
    }
}
