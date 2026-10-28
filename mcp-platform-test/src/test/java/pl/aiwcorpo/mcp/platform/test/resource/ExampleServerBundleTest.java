package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.policy.PolicyEngine;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/** A server that is none of the five systems (the template's mcp-example) gets a generated, signed test bundle. */
class ExampleServerBundleTest {

    @Test
    void theGeneratedBundleIsAcceptedByTheStarterAndPermitsTheCallByRisk() throws Exception {
        try (TestGateway gateway = TestGateway.start("mcp-example")) {
            McpPlatformProperties props = new McpPlatformProperties();
            McpPlatformProperties.Policy policy = props.getPolicy();
            var properties = gateway.serverProperties();
            policy.getMain().setUrl(properties.get("aiwcorpo.mcp.platform.policy.main.url"));
            policy.getMain().setKeyId("test-policy-bundle");
            policy.getMain().setPublicKey(properties.get("aiwcorpo.mcp.platform.policy.main.public-key"));
            policy.setAllowTestKeys(true);
            policy.setStartupTimeout(Duration.ofSeconds(3));

            try (PolicyEngine engine = PolicyEngine.create(policy, null, null)) {
                assertThat(engine.mainRevision()).contains("20261026130000-0e0e0e0");
                assertThat(engine.snapshot().policies().policies).extracting(p -> p.policyID)
                        .containsExactlyInAnyOrder("mcp-example-call-read", "mcp-example-call-write", "mcp-example-call-destructive");
            }
        }
    }
}
