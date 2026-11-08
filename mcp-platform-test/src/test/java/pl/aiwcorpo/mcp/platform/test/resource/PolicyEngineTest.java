package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.policy.PolicyEngine;
import pl.aiwcorpo.mcp.platform.policy.PolicyUnavailableException;
import pl.aiwcorpo.mcp.platform.policy.ServerPolicies;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/**
 * The policy data a server holds (C7, C14): the first push, a newer revision, an older or equal one, data this server cannot use, the
 * gate with and without data, and the one snapshot a call decides with. The documents are those of the test gateway, handed to the engine the way the
 * endpoint of the starter does; the endpoint itself is in {@code PolicyDataPushTest}.
 */
class PolicyEngineTest {

    private static final ServerPolicies POLICIES = ServerPolicies.load(PolicyEngineTest.class.getClassLoader());

    private TestGateway gateway;
    private SimpleMeterRegistry meters;
    private PolicyEngine engine;

    @BeforeEach
    void start() {
        gateway = TestGateway.start("mcp-jira");
        meters = new SimpleMeterRegistry();
        engine = PolicyEngine.create(POLICIES, meters);
    }

    @AfterEach
    void stop() {
        gateway.close();
    }

    /** What the gateway pushes now. */
    private PolicyEngine.Pushed push() {
        return engine.accept(gateway.policyData().current());
    }

    private double count(String name, String reason) {
        var counter = meters.find(name).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void aServerThatWasNotPushedAnythingHoldsNothingAndIsStale() {
        assertThat(engine.revision()).isEmpty();
        assertThatThrownBy(engine::snapshot).isInstanceOf(PolicyUnavailableException.class);

        PolicyEngine.Gated gated = engine.gate(OptionalLong.empty());

        assertThat(gated.decision()).extracting(d -> d.allowed(), d -> d.reason()).containsExactly(false, "policy_data_stale");
        assertThat(gated.snapshot()).isNull();
        assertThat(gated.heldRevision()).isEmpty();
    }

    @Test
    void theFirstPushIsTakenAndAStrictlyNewerRevisionReplacesIt() {
        PolicyEngine.Pushed first = push();

        assertThat(first).isEqualTo(new PolicyEngine.Pushed(gateway.policyData().revision(), true, "ok"));
        assertThat(engine.snapshot().allows("claude-code")).isTrue();

        gateway.policyData().allowedClients("claude-ai");
        PolicyEngine.Pushed second = push();

        assertThat(second.accepted()).isTrue();
        assertThat(second.revision()).isGreaterThan(first.revision());
        assertThat(engine.snapshot().allows("claude-code")).isFalse();
        assertThat(engine.snapshot().allows("claude-ai")).isTrue();
    }

    @Test
    void aRevisionThatIsNotHigherThanTheOneHeldIsNeverTakenAndTheAnswerSaysWhatIsKept() {
        gateway.policyData().allowedClients("claude-ai");
        PolicyEngine.Pushed held = push();
        var newer = gateway.policyData().current();

        assertThat(engine.accept(newer)).as("the same revision again").isEqualTo(new PolicyEngine.Pushed(held.revision(), false, "ok"));
        assertThat(count("mcp_server_policy_data_rejected_total", "same")).isEqualTo(1.0);

        gateway.policyData().nextRevision(held.revision() - 1);
        gateway.policyData().allowedClients("claude-code");
        var older = gateway.policyData().current();
        assertThat(older.revision()).isLessThan(held.revision());

        assertThat(engine.accept(older)).isEqualTo(new PolicyEngine.Pushed(held.revision(), false, "ok"));
        assertThat(engine.snapshot().revision()).isEqualTo(held.revision());
        assertThat(engine.snapshot().allows("claude-ai")).as("the older data is not used").isTrue();
        assertThat(count("mcp_server_policy_data_rejected_total", "older")).isEqualTo(1.0);
    }

    @Test
    void aScopeThisServerCannotUseIsSkippedAndListedTheRestOfTheDataIsUsed() {
        gateway.policyData().scopeMapping(Map.of("mcp-jira-proj-abc", List.of("Jira::Project/not a key", "Jira::Project/ABC")));

        push();

        assertThat(engine.snapshot().ignored()).extracting(skipped -> skipped.reason()).containsExactly("invalid_scope");
        assertThat(count("mcp_server_policy_data_scope_ignored_total", "invalid_scope")).isEqualTo(1.0);
    }

    @Test
    void anOverrideThatDoesNotValidateIsTakenAndRefusesEveryCallUntilItIsFixed() {
        gateway.policyData().override("override-opens-everything", "permit (principal, action, resource);");

        PolicyEngine.Pushed pushed = push();

        assertThat(pushed).as("the data is held, the gate says why the server refuses").extracting(PolicyEngine.Pushed::accepted,
                PolicyEngine.Pushed::gate).containsExactly(true, "overrides_invalid");
        assertThat(engine.gate(OptionalLong.empty()).decision()).extracting(d -> d.allowed(), d -> d.reason())
                .containsExactly(false, "overrides_invalid");
        assertThat(count("mcp_server_policy_data_rejected_total", "override_invalid")).isEqualTo(1.0);

        gateway.policyData().removeOverride("override-opens-everything");
        assertThat(push().gate()).isEqualTo("ok");

        assertThat(engine.gate(OptionalLong.empty()).decision().allowed()).isTrue();
    }

    @Test
    void theCallsAreRefusedUnlessTheAdministratorApprovedTheHashOfTheOwnPolicies() {
        assertThat(push().gate()).isEqualTo("ok");
        assertThat(engine.gate(OptionalLong.empty()).decision().allowed()).isTrue();

        gateway.policyData().unapproved();
        assertThat(push().gate()).isEqualTo("policies_not_approved");
        assertThat(engine.gate(OptionalLong.empty()).decision().reason()).isEqualTo("policies_not_approved");

        gateway.policyData().approvedPoliciesHash("sha256:" + "0".repeat(64));
        assertThat(push().gate()).isEqualTo("policies_differ");
        assertThat(engine.gate(OptionalLong.empty()).decision().reason()).isEqualTo("policies_differ");

        gateway.policyData().approved();
        assertThat(push().gate()).isEqualTo("ok");
        assertThat(engine.gate(OptionalLong.empty()).decision().allowed()).isTrue();
    }

    @Test
    void aTokenNamingANewerRevisionThanTheOneHeldIsStaleAndNothingIsFetched() {
        push();
        long held = engine.snapshot().revision();

        assertThat(engine.gate(OptionalLong.of(0)).decision().allowed()).as("prv 0 (a gateway without data) forces nothing").isTrue();
        assertThat(engine.gate(OptionalLong.of(held)).decision().allowed()).as("prv equal to the held revision").isTrue();
        PolicyEngine.Gated stale = engine.gate(OptionalLong.of(held + 5));

        assertThat(stale.decision().reason()).isEqualTo("policy_data_stale");
        assertThat(stale.heldRevision()).hasValue(held);
        assertThat(stale.requiredRevision()).hasValue(held + 5);
        assertThat(stale.snapshot()).isNull();
        assertThat(engine.revision()).as("the server never fetches").hasValue(held);
    }

    @Test
    void aStaleServerCatchesUpWhenTheGatewayPushes() {
        push();
        long held = engine.snapshot().revision();
        gateway.policyData().allowedClients("claude-ai");
        long newer = gateway.policyData().revision();

        assertThat(engine.gate(OptionalLong.of(newer)).decision().reason()).isEqualTo("policy_data_stale");
        push();

        assertThat(engine.gate(OptionalLong.of(newer)).decision().allowed()).isTrue();
        assertThat(newer).isGreaterThan(held);
    }

    @Test
    void whileNoDataIsHeldACallIsUpstreamUnavailableAndAuditedAsStale() {
        ch.qos.logback.classic.Logger auditLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("test.policy.unavailable");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> lines = new ch.qos.logback.core.read.ListAppender<>();
        lines.start();
        auditLog.addAppender(lines);
        try {
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
                .contains("\"event\":\"policy_decision\"", "\"decision\":\"deny\"", "\"reason\":\"policy_data_stale\"",
                        "\"errorCode\":\"upstream_unavailable\"", "\"outcome\":\"error\""));
    }

    @Test
    void theGateGivesTheSnapshotTheCallDecidesWithSoALaterSwapChangesNothingForIt() {
        push();
        PolicyEngine.Gated gated = engine.gate(OptionalLong.empty());
        long before = gated.snapshot().revision();

        gateway.policyData().unapproved();
        push();

        assertThat(gated.snapshot().revision()).isEqualTo(before);
        assertThat(gated.snapshot().approvedPoliciesHash()).isPresent();
        assertThat(engine.gate(OptionalLong.empty()).decision().reason()).isEqualTo("policies_not_approved");
    }

    @Test
    void aCallDecidesWithTheSnapshotItWasLetThroughWithEvenWhenTheDataIsSwappedBetweenTheGateAndTheResolver() {
        push();
        {
            var registry = new pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry(pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry.Unclassified.FAIL);
            registry.register(new RefTool("get_issue", "issueKey", "Jira::Issue"));
            var resolver = new pl.aiwcorpo.mcp.platform.resource.ResourceResolver<pl.aiwcorpo.mcp.cedar.entities.ResolvedResource>() {
                @Override
                public String resourceType() {
                    return "Jira::Issue";
                }

                @Override
                public java.util.Optional<pl.aiwcorpo.mcp.cedar.entities.ResolvedResource> fetch(String key) {
                    gateway.policyData().unapproved();
                    push();
                    return java.util.Optional.of(pl.aiwcorpo.mcp.cedar.entities.ResolvedResource.of("Jira::Issue", key, Map.of("key", key))
                            .withParent(pl.aiwcorpo.mcp.cedar.entities.ResolvedResource.of("Jira::Project", "ABC",
                                    Map.of("key", "ABC", "category", "Software")).withScopes(List.of("Jira::Project/ABC")))
                            .withScopes(List.of("Jira::Project/ABC")));
                }

                @Override
                public pl.aiwcorpo.mcp.cedar.entities.ResolvedResource describe(pl.aiwcorpo.mcp.cedar.entities.ResolvedResource fetched) {
                    return fetched;
                }
            };
            var policy = new pl.aiwcorpo.mcp.platform.resource.ResourcePolicy(engine,
                    new pl.aiwcorpo.mcp.platform.resource.ResourceResolvers(List.of(resolver)), registry,
                    new pl.aiwcorpo.mcp.platform.observability.AuditLogger("test.policy.swap", "mcp-jira"), "mcp-jira", "jira",
                    List.of("user-secret"), 200);
            var claims = new pl.aiwcorpo.mcp.platform.auth.CallerContext("6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11", "jkowalski",
                    List.of("mcp-jira-read"), "claude-code", "get_issue", "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", 1L, null);
            try (var context = pl.aiwcorpo.mcp.platform.resource.ResourceContext.open(claims, "get_issue", Map.of("issueKey", "ABC-1"))) {
                policy.authorizeCall();
                assertThat(policy.allows(pl.aiwcorpo.mcp.cedar.entities.ResolvedResource.of("Jira::Project", "ABC",
                        Map.of("key", "ABC", "category", "Software")).withScopes(List.of("Jira::Project/ABC"))))
                        .as("the per-element decision uses the same snapshot").isTrue();
            }
            try (var context = pl.aiwcorpo.mcp.platform.resource.ResourceContext.open(claims, "get_issue", Map.of("issueKey", "ABC-1"))) {
                assertThatThrownBy(policy::authorizeCall).isInstanceOf(pl.aiwcorpo.mcp.platform.error.SanitizedToolException.class)
                        .extracting(error -> ((pl.aiwcorpo.mcp.platform.error.SanitizedToolException) error).getCode())
                        .isEqualTo("upstream_unavailable");
                assertThatThrownBy(() -> policy.allows(pl.aiwcorpo.mcp.cedar.entities.ResolvedResource.of("Jira::Project", "ABC",
                        Map.of("key", "ABC", "category", "")))).as("a gate refusal is not turned into false")
                        .isInstanceOf(pl.aiwcorpo.mcp.platform.error.SanitizedToolException.class);
            }
        }
    }
}
