package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.policy.PolicyEngine;
import pl.aiwcorpo.mcp.platform.policy.PolicyUnavailableException;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import pl.aiwcorpo.mcp.policy.bundle.Channel;

/**
 * The bundle distribution of the starter (C7) against a repository served from the signed bundles of the contracts: the
 * first load, a newer revision, a bad signature, a rollback, the emergency bundle, and what survives a restart.
 */
class PolicyEngineTest {

    private static final String A = "20261026120000-a1b2c3d";
    private static final String B = "20261026130000-b2c3d4e";
    private static final String OVERRIDES = "20261026121500-d4e5f60";

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

    private McpPlatformProperties.Policy policy(Path stateDir) {
        McpPlatformProperties props = new McpPlatformProperties();
        Map<String, String> properties = gateway.serverProperties();
        McpPlatformProperties.Policy policy = props.getPolicy();
        policy.getMain().setUrl(properties.get("aiwcorpo.mcp.platform.policy.main.url"));
        policy.getMain().setKeyId("test-policy-bundle");
        policy.getMain().setPublicKey(properties.get("aiwcorpo.mcp.platform.policy.main.public-key"));
        if (properties.containsKey("aiwcorpo.mcp.platform.policy.overrides.url")) {
            policy.getOverrides().setUrl(properties.get("aiwcorpo.mcp.platform.policy.overrides.url"));
            policy.getOverrides().setKeyId("test-policy-overrides");
            policy.getOverrides().setPublicKey(properties.get("aiwcorpo.mcp.platform.policy.overrides.public-key"));
        }
        policy.setAllowTestKeys(true);
        policy.setStartupTimeout(Duration.ofSeconds(2));
        policy.setOverridesGrace(Duration.ofMillis(400));
        if (stateDir != null) {
            policy.setStateDir(stateDir.toString());
        }
        return policy;
    }

    private PolicyEngine engine(Path stateDir) throws Exception {
        return PolicyEngine.create(policy(stateDir), null, meters);
    }

    private double rejected(String reason) {
        var counter = meters.find("mcp_server_policy_bundle_rejected_total").tag("channel", "main").tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theFirstMainBundleIsLoadedAndANewerOneReplacesIt() throws Exception {
        gateway.main().publish(A);
        try (PolicyEngine engine = engine(null)) {
            assertThat(engine.mainRevision()).contains(A);

            gateway.main().publish(B);
            engine.refresh(Channel.MAIN);

            assertThat(engine.mainRevision()).contains(B);
        }
    }

    @Test
    void aBundleWithABadSignatureLeavesThePreviousOneAndIsCounted() throws Exception {
        gateway.main().publish(A);
        try (PolicyEngine engine = engine(null)) {
            gateway.main().publish(B, "policies-" + B + ".tar.gz.sig-by-overrides-key");
            engine.refresh(Channel.MAIN);
            assertThat(rejected("bad_signature")).as("one rejected revision is counted once, not every pull").isEqualTo(1.0);
        }
    }

    @Test
    void anOlderSignedBundleIsNotAccepted() throws Exception {
        try (PolicyEngine engine = engine(null)) {
            gateway.main().publish(A);
            engine.refresh(Channel.MAIN);

            assertThat(engine.mainRevision()).contains(B);
            assertThat(rejected("not_newer")).isEqualTo(1.0);
        }
    }

    @Test
    void aRepositoryThatIsDownLeavesThePreviousBundleAndTheServerKeepsDeciding() throws Exception {
        try (PolicyEngine engine = engine(null)) {
            gateway.main().offline(true);
            engine.refresh(Channel.MAIN);

            assertThat(engine.mainRevision()).contains(B);
            assertThat(engine.snapshot().main().revision()).isEqualTo(B);
        }
    }

    private static byte[] resource(String path) throws Exception {
        try (java.io.InputStream in = PolicyEngineTest.class.getResourceAsStream(path)) {
            return java.util.Objects.requireNonNull(in, path).readAllBytes();
        }
    }

    /** The bundle of the contracts 3.1.1 for revision B: the same policies on the schema before CC-136 (other bytes of mcp.cedarschema). */
    private void publishBundleOfContracts311() throws Exception {
        gateway.main().publishRaw(B, resource("/contracts-3.1.1-bundle/policies-" + B + ".tar.gz"),
                resource("/contracts-3.1.1-bundle/policies-" + B + ".tar.gz.sig"));
    }

    @Test
    void aBundleOnTheSchemaOfContracts3_1_IsRejectedAsASchemaMismatchAndTheCurrentOneStays() throws Exception {
        gateway.main().publish(A);
        try (PolicyEngine engine = engine(null)) {
            publishBundleOfContracts311();
            engine.refresh(Channel.MAIN);

            assertThat(engine.mainRevision()).contains(A);
            assertThat(rejected("schema_mismatch")).isEqualTo(1.0);
        }
    }

    @Test
    void aFirstBundleOnTheSchemaOfContracts3_1_DoesNotStartTheServer() throws Exception {
        publishBundleOfContracts311();

        assertThatThrownBy(() -> engine(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No policy bundle could be loaded");
        assertThat(rejected("schema_mismatch")).isGreaterThanOrEqualTo(1.0);
    }

    /** The bundle of the contracts 4.1.0 for revision B: it still has the virtual server rules and the schema with vsScopes (other bytes of mcp.cedarschema). */
    private void publishBundleOfContracts410() throws Exception {
        gateway.main().publishRaw(B, resource("/contracts-4.1.0-bundle/policies-" + B + ".tar.gz"),
                resource("/contracts-4.1.0-bundle/policies-" + B + ".tar.gz.sig"));
    }

    @Test
    void aBundleOnTheSchemaOfContracts4_IsRejectedAsASchemaMismatchAndTheCurrentOneStays() throws Exception {
        gateway.main().publish(A);
        try (PolicyEngine engine = engine(null)) {
            publishBundleOfContracts410();
            engine.refresh(Channel.MAIN);

            assertThat(engine.mainRevision()).contains(A);
            assertThat(rejected("schema_mismatch")).isEqualTo(1.0);
        }
    }

    @Test
    void aFirstBundleOnTheSchemaOfContracts4_DoesNotStartTheServer() throws Exception {
        publishBundleOfContracts410();

        assertThatThrownBy(() -> engine(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No policy bundle could be loaded");
        assertThat(rejected("schema_mismatch")).isGreaterThanOrEqualTo(1.0);
    }

    private PolicyEngine engineWithDevKey(boolean devstackProfile) throws Exception {
        String dev = "20261026151100-17ba079";
        gateway.main().publish(dev, "policies-" + dev + ".tar.gz.sig-by-dev-key");
        McpPlatformProperties.Policy policy = policy(null);
        policy.getMain().setKeyId("dev-policy-main");
        policy.getMain().setPublicKey(new String(resource("/mcp-contracts/test-vectors/keys/dev-policy-bundle.pub.pem"),
                java.nio.charset.StandardCharsets.UTF_8));
        return PolicyEngine.create(policy, null, meters, devstackProfile);
    }

    @Test
    void aDevKeyIdIsAcceptedOnlyInDevMode() throws Exception {
        gateway.main().offline(false);
        assertThatThrownBy(() -> engineWithDevKey(false)).as("allow-test-keys alone is not dev mode").isInstanceOf(IllegalStateException.class);
        assertThat(rejected("test_key_not_allowed")).isGreaterThanOrEqualTo(1.0);
        try (PolicyEngine engine = engineWithDevKey(true)) {
            assertThat(engine.mainRevision()).contains("20261026151100-17ba079");
        }
    }

    @Test
    void withoutABundleTheServerDoesNotStart() {
        gateway.main().offline(true);

        assertThatThrownBy(() -> engine(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No policy bundle could be loaded");
    }

    @Test
    void aBundleSignedWithAnotherKeyDoesNotStartTheServer() {
        gateway.main().publish(B, "policies-" + B + ".tar.gz.sig-by-overrides-key");

        assertThatThrownBy(() -> engine(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theEmergencyBundleIsLoadedOnTopOfTheMainOne() throws Exception {
        gateway.overrides().publish(OVERRIDES);

        try (PolicyEngine engine = engine(null)) {
            assertThat(engine.snapshot().overrides()).isPresent();
            assertThat(engine.snapshot().overrides().orElseThrow().revision()).isEqualTo(OVERRIDES);
        }
    }

    @Test
    void anOlderBundleIsNotAcceptedAfterARestartEither(@TempDir Path state) throws Exception {
        try (PolicyEngine first = engine(state)) {
            assertThat(first.mainRevision()).contains(B);
        }
        gateway.main().publish(A);

        assertThatThrownBy(() -> engine(state)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void afterARestartTheCallsWaitForTheEmergencyBundleThatWasActive(@TempDir Path state) throws Exception {
        gateway.overrides().publish(OVERRIDES);
        try (PolicyEngine first = engine(state)) {
            assertThat(first.snapshot().overrides()).isPresent();
        }
        gateway.overrides().offline(true);

        try (PolicyEngine second = PolicyEngine.create(policy(state), null, meters)) {
            assertThatThrownBy(second::snapshot).isInstanceOf(PolicyUnavailableException.class)
                    .hasMessageContaining("overrides");
            Thread.sleep(600);
            assertThat(second.snapshot().main().revision()).as("after the grace the server serves, with an alarm").isEqualTo(B);
        }
    }

    @Test
    void whileNoPolicyCanBeUsedACallIsUpstreamUnavailableAndAuditedAsPolicyUnavailable(@TempDir Path state) throws Exception {
        gateway.overrides().publish(OVERRIDES);
        try (PolicyEngine first = engine(state)) {
            assertThat(first.snapshot().overrides()).isPresent();
        }
        gateway.overrides().offline(true);
        ch.qos.logback.classic.Logger auditLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("test.policy.unavailable");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> lines = new ch.qos.logback.core.read.ListAppender<>();
        lines.start();
        auditLog.addAppender(lines);
        try (PolicyEngine second = PolicyEngine.create(policy(state), null, meters)) {
            pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry registry =
                    new pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry(pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry.Unclassified.FAIL);
            registry.register(new ReadThingTool());
            pl.aiwcorpo.mcp.platform.resource.ResourcePolicy policy = new pl.aiwcorpo.mcp.platform.resource.ResourcePolicy(second,
                    new pl.aiwcorpo.mcp.platform.resource.ResourceResolvers(java.util.List.of()), registry,
                    new pl.aiwcorpo.mcp.platform.observability.AuditLogger("test.policy.unavailable", "mcp-jira"), "mcp-jira", "jira",
                    java.util.List.of("user-secret"), 200);
            pl.aiwcorpo.mcp.platform.auth.CallerContext claims = new pl.aiwcorpo.mcp.platform.auth.CallerContext(
                    "6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11", "jkowalski", java.util.List.of("mcp-jira-read"), "claude-code",
                    "read_thing", "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", "00000000000000-0000000", null);
            try (pl.aiwcorpo.mcp.platform.resource.ResourceContext context =
                         pl.aiwcorpo.mcp.platform.resource.ResourceContext.open(claims, "read_thing", Map.of())) {
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
                .contains("\"event\":\"policy_decision\"", "\"decision\":\"deny\"", "\"reason\":\"policy_unavailable\"",
                        "\"errorCode\":\"upstream_unavailable\"", "\"outcome\":\"error\""));
    }

    private static java.util.Optional<String> awaitMainRevision(PolicyEngine engine, String revision) throws InterruptedException {
        for (int i = 0; i < 100 && !engine.mainRevision().equals(java.util.Optional.of(revision)); i++) {
            Thread.sleep(50);
        }
        return engine.mainRevision();
    }

    @Test
    void aRepositoryThatStopsInTheMiddleOfTheBodyEndsThePullAtTheTimeLimitAndTheHintDoesNotWait() throws Exception {
        gateway.main().publish(A);
        McpPlatformProperties.Policy policy = policy(null);
        policy.setReadTimeout(Duration.ofMillis(800));
        policy.getMain().setRefreshInterval(Duration.ofHours(1));
        gateway.overrides().publish(OVERRIDES);
        McpPlatformProperties.Policy withOverrides = policy(null);
        withOverrides.setReadTimeout(Duration.ofMillis(800));
        withOverrides.getMain().setRefreshInterval(Duration.ofHours(1));
        try (PolicyEngine engine = PolicyEngine.create(withOverrides, null, meters)) {
            gateway.main().publish(B).stall(true);

            long start = System.nanoTime();
            engine.hintRevision(B);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("the hint returns at once").isLessThan(Duration.ofMillis(300));

            start = System.nanoTime();
            engine.refresh(Channel.MAIN);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("a stalled body ends at the limit")
                    .isLessThan(Duration.ofSeconds(3));
            assertThat(engine.mainRevision()).contains(A);
            gateway.overrides().publish("20261026121700-f607182");
            start = System.nanoTime();
            engine.refresh(Channel.OVERRIDES);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).as("a stuck main does not hold up overrides")
                    .isLessThan(Duration.ofSeconds(3));
        } finally {
            gateway.main().stall(false);
        }
    }

    @Test
    void aTokenNamingANewerRevisionMakesTheServerPullAtOnce() throws Exception {
        gateway.main().publish(A);
        McpPlatformProperties.Policy policy = policy(null);
        policy.getMain().setRefreshInterval(Duration.ofHours(1));
        try (PolicyEngine engine = PolicyEngine.create(policy, null, meters)) {
            gateway.main().publish(B);

            engine.hintRevision("00000000000000-0000000");
            assertThat(engine.mainRevision()).as("the all-zero revision of a gateway without bundle is no hint").contains(A);
            engine.hintRevision(B);

            assertThat(awaitMainRevision(engine, B)).contains(B);
        }
    }
}
