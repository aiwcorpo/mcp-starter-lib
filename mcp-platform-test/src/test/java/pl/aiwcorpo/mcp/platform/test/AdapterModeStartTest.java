package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import pl.aiwcorpo.mcp.platform.autoconfigure.DeploymentSignalAssumption;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformPresenceCheck;
import pl.aiwcorpo.mcp.platform.manifest.ManifestCheck;
import pl.aiwcorpo.mcp.platform.policy.PolicyEngine;
import pl.aiwcorpo.mcp.platform.test.adapter.AdapterServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A server in {@code receiver-mode: adapter} started as a real application (not through the test framework, which switches the presence
 * check off): the platform defines no policy engine and no resource checks there, so the presence check must not ask for them.
 */
class AdapterModeStartTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-foreign");

    @AfterAll
    static void stop() {
        GATEWAY.close();
    }

    @Test
    void anAdapterStartsWithoutPolicyBeansAndServesAManifestWithoutPolicies() {
        DeploymentSignalAssumption.assumeDeveloperMachine();
        Map<String, Object> properties = new HashMap<>(GATEWAY.serverProperties());
        properties.put("spring.ai.mcp.server.name", "adapter-mode-test");
        properties.put("aiwcorpo.mcp.platform.internal-jwt.receiver-mode", "adapter");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AdapterServer.class).web(WebApplicationType.SERVLET)
                .properties(properties).properties("server.port=0").profiles("dev").run()) {
            assertThat(context.getBeanNamesForType(PolicyEngine.class)).isEmpty();
            assertThat(context.containsBean("resourcePolicy")).isFalse();
            JsonNode manifest = JsonMapper.builder().build().readTree(context.getBean(ManifestCheck.class).manifest().orElseThrow());
            assertThat(manifest.get("source").asString()).isEqualTo("adapter");
            assertThat(manifest.get("resourceTypes")).isEmpty();
            assertThat(manifest.has("policies")).isFalse();
            assertThat(manifest.has("policiesHash")).isFalse();

            // The gateway pushes to every server alike (C14): the adapter takes the document and keeps the highest revision, with no gate to refuse.
            java.net.URI origin = java.net.URI.create("http://127.0.0.1:"
                    + ((org.springframework.boot.web.server.context.WebServerApplicationContext) context).getWebServer().getPort());
            PolicyDataEndpoint.Ack again = GATEWAY.policyData().attach(origin);
            assertThat(again).as("the first push went out when the web server started").extracting(PolicyDataEndpoint.Ack::status,
                    PolicyDataEndpoint.Ack::accepted, PolicyDataEndpoint.Ack::gate).containsExactly(200, false, "ok");
            assertThat(again.revision()).isEqualTo(GATEWAY.policyData().revision());
        }
    }

    @Test
    void theBeansAnAdapterMustHaveExcludeThePolicyOnesAndANativeServerKeepsAllOfThem() {
        MockEnvironment adapter = new MockEnvironment().withProperty("aiwcorpo.mcp.platform.internal-jwt.receiver-mode", "adapter");

        assertThat(PlatformPresenceCheck.requiredBeans(adapter)).doesNotContain("policyEngine", "resourcePolicy")
                .contains("platformConfigurationGuard", "internalJwtVerifier");
        assertThat(PlatformPresenceCheck.requiredBeans(new MockEnvironment())).isEqualTo(PlatformPresenceCheck.REQUIRED_BEANS);
        assertThat(PlatformPresenceCheck.requiredBeans(new MockEnvironment()
                .withProperty("aiwcorpo.mcp.platform.internal-jwt.receiver-mode", "native"))).contains("policyEngine");
    }
}
