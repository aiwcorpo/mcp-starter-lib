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
