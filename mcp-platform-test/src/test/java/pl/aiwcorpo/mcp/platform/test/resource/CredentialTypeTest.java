package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.observability.AuditLogger;
import pl.aiwcorpo.mcp.platform.policy.PolicyEngine;
import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolvers;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/**
 * {@code context.credentialType} (CC-136): every policy request carries the type from the server's configuration, so a rule that
 * depends on it applies, and a server without the type has no policy at all.
 */
class CredentialTypeTest {

    private static final String FORBID_USER_SECRET = """
            @id("no-user-secret-calls")
            forbid (principal, action == Action::"call", resource is Tool)
            when { context has credentialType && context.credentialType == "user-secret" };
            """;

    private static TestGateway gateway;
    private static PolicyEngine engine;

    @BeforeAll
    static void start() throws Exception {
        gateway = TestGateway.start("mcp-jira");
        gateway.main().publishGenerated("20261026130000-0e0e0e0", "mcp-jira", Map.of("no-user-secret.cedar", FORBID_USER_SECRET));
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

    private static ResourcePolicy policyWith(java.util.List<String> credentialTypes) {
        ToolRiskRegistry registry = new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL);
        registry.register(new ReadThingTool());
        return new ResourcePolicy(engine, new ResourceResolvers(List.of()), registry, new AuditLogger("test.credential.type", "mcp-jira"),
                "mcp-jira", "jira", credentialTypes, 200);
    }

    private static ResourceContext open() {
        CallerContext claims = new CallerContext("6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11", "jkowalski", List.of("mcp-jira-read"),
                "claude-code", null, null, "read_thing", "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", "00000000000000-0000000", null);
        return ResourceContext.open(claims, "read_thing", Map.of());
    }

    @Test
    void aRuleOnTheCredentialTypeAppliesToTheTypeOfTheServersConfiguration() {
        try (ResourceContext context = open()) {
            assertThatThrownBy(policyWith(java.util.List.of("user-secret"))::authorizeCall).isInstanceOf(SanitizedToolException.class)
                    .extracting(error -> ((SanitizedToolException) error).getCode()).isEqualTo("access_denied");
            policyWith(java.util.List.of("service-account")).authorizeCall();
            policyWith(java.util.List.of("user-oauth")).authorizeCall();
        }
    }

    @Test
    void aServerWithoutTheTypeHasNoPolicy() {
        assertThatThrownBy(() -> policyWith(java.util.List.of())).isInstanceOf(IllegalStateException.class).hasMessageContaining("credential-type");
    }

    @Test
    void aTypeOfNoneOfTheFourIsAnInternalErrorNotAnUnconditionedCheck() {
        try (ResourceContext context = open()) {
            assertThatThrownBy(policyWith(java.util.List.of("password"))::authorizeCall).isInstanceOf(SanitizedToolException.class)
                    .extracting(error -> ((SanitizedToolException) error).getCode()).isEqualTo("internal_error");
        }
    }
}
