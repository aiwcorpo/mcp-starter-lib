package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.policy.PolicyEngine;
import pl.aiwcorpo.mcp.platform.policy.PolicyUnavailableException;
import pl.aiwcorpo.mcp.platform.policy.ServerPolicies;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/**
 * The distribution of the policy data (C7) against the endpoint of the test gateway: the first load, a newer revision, data this server
 * cannot use, a gateway that is down or does not know the server, and the refresh a token asks for.
 */
class PolicyEngineTest {

    private static final ServerPolicies POLICIES = ServerPolicies.load(PolicyEngineTest.class.getClassLoader());

    private TestGateway gateway;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void start() {
        gateway = TestGateway.start("mcp-jira");
        meters = new SimpleMeterRegistry();
    }

    @AfterEach
    void stop() {
        gateway.close();
    }

    private McpPlatformProperties.PolicyData config() {
        McpPlatformProperties.PolicyData config = new McpPlatformProperties().getPolicyData();
        config.setUrl(gateway.serverProperties().get("aiwcorpo.mcp.platform.policy-data.url"));
        config.setStartupTimeout(Duration.ofSeconds(2));
        config.setRefreshInterval(Duration.ofHours(1));
        config.setRetryInterval(Duration.ofHours(1));
        return config;
    }

    private PolicyEngine engine(McpPlatformProperties.PolicyData config) {
        return PolicyEngine.create(config, POLICIES, null, meters);
    }

    private double count(String name, String reason) {
        var counter = meters.find(name).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theFirstDataIsLoadedAndANewerRevisionReplacesIt() {
        try (PolicyEngine engine = engine(config())) {
            long first = engine.revision().orElseThrow();
            assertThat(first).isEqualTo(gateway.policyData().revision());
            assertThat(engine.snapshot().allows("claude-code")).isTrue();

            gateway.policyData().allowedClients("claude-ai");
            engine.refresh();

            assertThat(engine.revision()).hasValue(gateway.policyData().revision());
            assertThat(gateway.policyData().revision()).isGreaterThan(first);
            assertThat(engine.snapshot().allows("claude-code")).isFalse();
            assertThat(engine.snapshot().allows("claude-ai")).isTrue();
        }
    }

    @Test
    void anUnchangedRevisionIsAskedForWithItsEtagAndAnswered304() {
        try (PolicyEngine engine = engine(config())) {
            int before = gateway.policyData().requests();
            engine.refresh();
            engine.refresh();

            assertThat(gateway.policyData().requests()).isEqualTo(before + 2);
            assertThat(engine.revision()).hasValue(gateway.policyData().revision());
        }
    }

    @Test
    void dataThisServerCannotUseLeavesThePreviousOneAndIsCountedOnce() {
        try (PolicyEngine engine = engine(config())) {
            long previous = engine.revision().orElseThrow();
            gateway.policyData().scopeMapping(Map.of("mcp-jira-proj-abc", List.of("Jira::Project/not a key", "Jira::Project/ABC")));

            engine.refresh();

            assertThat(engine.revision()).as("a scope of ours that is not valid is skipped and listed, the rest of the data is used")
                    .hasValue(gateway.policyData().revision());
            assertThat(engine.snapshot().ignored()).extracting(skipped -> skipped.reason()).containsExactly("invalid_scope");
            assertThat(count("mcp_server_policy_data_scope_ignored_total", "invalid_scope")).isEqualTo(1.0);
            assertThat(previous).isLessThan(engine.snapshot().revision());
        }
    }

    @Test
    void anOverrideThatDoesNotValidateRefusesEveryCallUntilItIsFixed() {
        try (PolicyEngine engine = engine(config())) {
            gateway.policyData().override("override-opens-everything", "permit (principal, action, resource);");
            engine.refresh();

            assertThat(engine.gate(java.util.OptionalLong.empty())).extracting(d -> d.allowed(), d -> d.reason())
                    .containsExactly(false, "overrides_invalid");
            assertThat(count("mcp_server_policy_data_rejected_total", "override_invalid")).isEqualTo(1.0);

            gateway.policyData().removeOverride("override-opens-everything");
            engine.refresh();

            assertThat(engine.gate(java.util.OptionalLong.empty()).allowed()).isTrue();
        }
    }

    @Test
    void theCallsAreRefusedUnlessTheAdministratorApprovedTheHashOfTheOwnPolicies() {
        try (PolicyEngine engine = engine(config())) {
            assertThat(engine.gate(java.util.OptionalLong.empty()).allowed()).isTrue();

            gateway.policyData().unapproved();
            engine.refresh();
            assertThat(engine.gate(java.util.OptionalLong.empty()).reason()).isEqualTo("policies_not_approved");

            gateway.policyData().approvedPoliciesHash("sha256:" + "0".repeat(64));
            engine.refresh();
            assertThat(engine.gate(java.util.OptionalLong.empty()).reason()).isEqualTo("policies_differ");

            gateway.policyData().approved();
            engine.refresh();
            assertThat(engine.gate(java.util.OptionalLong.empty()).allowed()).isTrue();
        }
    }

    @Test
    void aRevisionThatIsNotHigherThanTheOneHeldIsNeverTaken() throws Exception {
        try (PolicyEngine engine = engine(config())) {
            gateway.policyData().allowedClients("claude-ai");
            engine.refresh();
            long held = engine.snapshot().revision();

            gateway.policyData().nextRevision(held - 1);
            gateway.policyData().allowedClients("claude-code");
            engine.refresh();

            assertThat(gateway.policyData().revision()).isLessThanOrEqualTo(held);
            assertThat(engine.snapshot().revision()).isEqualTo(held);
            assertThat(engine.snapshot().allows("claude-ai")).as("the older data is not used").isTrue();
            assertThat(count("mcp_server_policy_data_rejected_total", "older")).isEqualTo(1.0);
        }
    }

    @Test
    void aGatewayThatIsDownLeavesTheDataInUse() {
        try (PolicyEngine engine = engine(config())) {
            gateway.policyData().offline(true);
            engine.refresh();

            assertThat(engine.snapshot().revision()).isEqualTo(gateway.policyData().revision());
            assertThat(count("mcp_server_policy_data_fetch_failed_total", "io")).isEqualTo(1.0);
        }
    }

    @Test
    void aServerStartedWithoutAGatewayRefusesUntilItHasDataAndThenLoadsIt() {
        gateway.policyData().offline(true);
        McpPlatformProperties.PolicyData config = config();
        config.setStartupTimeout(Duration.ofMillis(300));
        try (PolicyEngine engine = engine(config)) {
            assertThatThrownBy(engine::snapshot).isInstanceOf(PolicyUnavailableException.class);

            gateway.policyData().offline(false);
            engine.refresh();

            assertThat(engine.snapshot().revision()).isEqualTo(gateway.policyData().revision());
        }
    }

    @Test
    void aServerTheGatewayDoesNotKnowYetGetsNoDataAndKeepsAsking() {
        gateway.policyData().unregistered(true);
        McpPlatformProperties.PolicyData config = config();
        config.setStartupTimeout(Duration.ofMillis(300));
        try (PolicyEngine engine = engine(config)) {
            assertThat(engine.revision()).isEmpty();
            assertThatThrownBy(engine::snapshot).isInstanceOf(PolicyUnavailableException.class);
        }
    }

    @Test
    void whileNoDataIsLoadedACallIsUpstreamUnavailableAndAuditedAsPolicyUnavailable() {
        gateway.policyData().offline(true);
        McpPlatformProperties.PolicyData config = config();
        config.setStartupTimeout(Duration.ofMillis(200));
        ch.qos.logback.classic.Logger auditLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("test.policy.unavailable");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> lines = new ch.qos.logback.core.read.ListAppender<>();
        lines.start();
        auditLog.addAppender(lines);
        try (PolicyEngine engine = engine(config)) {
            var registry = new pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry(pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry.Unclassified.FAIL);
            registry.register(new RefTool("get_issue", "issueKey", "Jira::Issue"));
            var policy = new pl.aiwcorpo.mcp.platform.resource.ResourcePolicy(engine,
                    new pl.aiwcorpo.mcp.platform.resource.ResourceResolvers(List.of()), registry,
                    new pl.aiwcorpo.mcp.platform.observability.AuditLogger("test.policy.unavailable", "mcp-jira"), "mcp-jira", "jira",
                    List.of("user-secret"), 200);
            var claims = new pl.aiwcorpo.mcp.platform.auth.CallerContext("6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11", "jkowalski",
                    List.of("mcp-jira-read"), "claude-code", "get_issue", "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", 0L, null);
            try (var context = pl.aiwcorpo.mcp.platform.resource.ResourceContext.open(claims, "get_issue", Map.of("issueKey", "ABC-1"))) {
                assertThatThrownBy(policy::authorizeCall).isInstanceOf(pl.aiwcorpo.mcp.platform.error.SanitizedToolException.class)
                        .satisfies(error -> {
                            var refused = (pl.aiwcorpo.mcp.platform.error.SanitizedToolException) error;
                            assertThat(refused.getCode()).isEqualTo("upstream_unavailable");
                            assertThat(refused.toSafeError().message()).isEqualTo("Policies are not available. Try again later.");
                        });
            }
        } finally {
            auditLog.detachAppender(lines);
        }
        assertThat(lines.list).singleElement().satisfies(line -> assertThat(line.getFormattedMessage())
                .contains("\"event\":\"policy_decision\"", "\"decision\":\"deny\"", "\"reason\":\"policy_data_unavailable\"",
                        "\"errorCode\":\"upstream_unavailable\"", "\"outcome\":\"error\""));
    }

    @Test
    void aTokenNamingANewerRevisionMakesTheServerAskBeforeItDecidesAndTheAnswerDecides() {
        try (PolicyEngine engine = engine(config())) {
            long held = engine.snapshot().revision();
            gateway.policyData().allowedClients("claude-ai");
            long newer = gateway.policyData().revision();

            assertThat(engine.gate(java.util.OptionalLong.of(0)).allowed()).as("prv 0 (a gateway without data) forces nothing").isTrue();
            assertThat(engine.revision()).hasValue(held);
            assertThat(engine.gate(java.util.OptionalLong.of(newer)).allowed()).isTrue();

            assertThat(engine.revision()).as("the data was asked for before the decision").hasValue(newer);
        }
    }

    @Test
    void aTokenNamingARevisionTheGatewayCannotServeIsRefusedNotEvaluatedWithOlderData() {
        try (PolicyEngine engine = engine(config())) {
            long held = engine.snapshot().revision();

            assertThat(engine.gate(java.util.OptionalLong.of(held + 5)).reason()).isEqualTo("policy_data_unavailable");
            assertThat(engine.revision()).hasValue(held);
        }
    }

    @Test
    void aGatewayThatStopsInTheMiddleOfTheBodyEndsTheRequestAtTheTimeLimitAndTheCallIsRefused() {
        McpPlatformProperties.PolicyData config = config();
        config.setReadTimeout(Duration.ofMillis(800));
        try (PolicyEngine engine = engine(config)) {
            long loaded = engine.revision().orElseThrow();
            gateway.policyData().allowedClients("claude-ai").stall(true);
            long later = gateway.policyData().revision();

            long start = System.nanoTime();
            assertThat(engine.gate(java.util.OptionalLong.of(later)).reason()).isEqualTo("policy_data_unavailable");
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("a stalled body ends at the limit").isLessThan(Duration.ofSeconds(3));
            assertThat(engine.revision()).hasValue(loaded);
        } finally {
            gateway.policyData().stall(false);
        }
    }
}
