package pl.aiwcorpo.mcp.platform.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.actuate.endpoint.SimpleStatusAggregator;
import org.springframework.boot.health.contributor.Status;

class CapabilityProbeRunnerTest {

    private static final Set<String> FULL = Set.of("search_v3", "attachments");
    private static final Set<String> BASELINE = Set.of("attachments");

    private static CapabilityProbe probe(String name, Callable body) {
        return new CapabilityProbe() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Capabilities probe() throws Exception {
                return body.call();
            }
        };
    }

    interface Callable {
        Capabilities call() throws Exception;
    }

    private static CapabilityProbeRunner runner(BackendCapabilities store, SimpleMeterRegistry meters, Duration timeout,
                                                CapabilityProbe... probes) {
        return new CapabilityProbeRunner(List.of(probes), store, meters, timeout, Duration.ofMinutes(5), Duration.ofSeconds(30));
    }

    @Test
    void beforeTheFirstAnswerTheBackendIsUnavailableAndNoFeatureIsAssumed() {
        BackendCapabilities store = new BackendCapabilities();

        assertThat(store.of("jira").state()).isEqualTo(Capabilities.State.UNAVAILABLE);
        assertThat(store.of("jira").supports("search_v3")).isFalse();
        assertThat(store.isDegraded("jira")).isTrue();
    }

    @Test
    void aKnownVersionIsSupportedWithTheFullFeatureSet() {
        BackendCapabilities store = new BackendCapabilities();
        CapabilityProbe jira = probe("jira", () -> Capabilities.supported("9.12.3", FULL));

        runner(store, null, Duration.ofSeconds(2), jira).runOnce(jira);

        assertThat(store.of("jira").state()).isEqualTo(Capabilities.State.SUPPORTED);
        assertThat(store.of("jira").supports("search_v3")).isTrue();
        assertThat(store.isDegraded("jira")).isFalse();
    }

    @Test
    void anUnknownVersionKeepsTheServerUpInTheBaselineMode() {
        BackendCapabilities store = new BackendCapabilities();
        CapabilityProbe jira = probe("jira", () -> Capabilities.degraded("9.4.0", BASELINE, "older than 9.12"));

        runner(store, null, Duration.ofSeconds(2), jira).runOnce(jira);

        assertThat(store.isDegraded("jira")).isTrue();
        assertThat(store.of("jira").supports("attachments")).isTrue();
        assertThat(store.of("jira").supports("search_v3")).isFalse();
    }

    @Test
    void aFailingProbeIsUnavailableAndOnlyTheExceptionClassIsKept() {
        BackendCapabilities store = new BackendCapabilities();
        CapabilityProbe jira = probe("jira", () -> {
            throw new IllegalStateException("GET https://jira.internal/rest?token=s3cr3t failed");
        });

        runner(store, null, Duration.ofSeconds(2), jira).runOnce(jira);

        assertThat(store.of("jira").state()).isEqualTo(Capabilities.State.UNAVAILABLE);
        assertThat(store.of("jira").detail()).isEqualTo("probe failed with IllegalStateException").doesNotContain("s3cr3t");
    }

    @Test
    void aProbeThatDoesNotAnswerInTimeIsUnavailable() {
        BackendCapabilities store = new BackendCapabilities();
        CountDownLatch never = new CountDownLatch(1);
        CapabilityProbe jira = probe("jira", () -> {
            never.await();
            return Capabilities.supported("9.12.3", FULL);
        });

        runner(store, null, Duration.ofMillis(100), jira).runOnce(jira);

        assertThat(store.of("jira").state()).isEqualTo(Capabilities.State.UNAVAILABLE);
        assertThat(store.of("jira").detail()).startsWith("no answer within");
    }

    @Test
    void theVersionAndTheDetailFromTheBackendAreCleanedBeforeTheyReachALog() {
        Capabilities capabilities = Capabilities.degraded("9.12\n<script>" + "x".repeat(300), BASELINE, "bad\r\nline");

        assertThat(capabilities.backendVersion()).doesNotContain("\n", "<", ">").hasSizeLessThanOrEqualTo(120);
        assertThat(capabilities.detail()).doesNotContain("\r", "\n");
    }

    @Test
    void anUnavailableBackendCannotHaveFeatures() {
        assertThatThrownBy(() -> new Capabilities(Capabilities.State.UNAVAILABLE, null, Set.of("x"), ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aChangeOfStateIsOneLogLineAndARepeatIsNone() {
        Logger logger = (Logger) LoggerFactory.getLogger(CapabilityProbeRunner.class);
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        try {
            BackendCapabilities store = new BackendCapabilities();
            AtomicReference<Capabilities> answer = new AtomicReference<>(Capabilities.supported("9.12.3", FULL));
            CapabilityProbe jira = probe("jira", answer::get);
            CapabilityProbeRunner runner = runner(store, null, Duration.ofSeconds(2), jira);

            runner.runOnce(jira);
            runner.runOnce(jira);
            answer.set(Capabilities.degraded("10.0.0", BASELINE, "newer than tested"));
            runner.runOnce(jira);

            assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage)
                    .hasSize(2)
                    .satisfies(messages -> {
                        assertThat(messages.get(0)).contains("state=supported", "version=9.12.3");
                        assertThat(messages.get(1)).contains("state=degraded", "newer than tested");
                    });
        } finally {
            logger.detachAppender(lines);
        }
    }

    @Test
    void theMetricIsOneForTheCurrentStateOnly() {
        BackendCapabilities store = new BackendCapabilities();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CapabilityProbe jira = probe("jira", () -> Capabilities.degraded("10.0.0", BASELINE, "newer"));
        CapabilityProbeRunner runner = runner(store, meters, Duration.ofSeconds(2), jira);

        runner.runOnce(jira);

        assertThat(meters.get("mcp.backend.capability").tag("probe", "jira").tag("state", "degraded").gauge().value())
                .isEqualTo(1.0);
        assertThat(meters.get("mcp.backend.capability").tag("probe", "jira").tag("state", "supported").gauge().value())
                .isEqualTo(0.0);
        assertThat(meters.get("mcp.backend.capability").tag("probe", "jira").tag("state", "unavailable").gauge().value())
                .isEqualTo(0.0);
    }

    @Test
    void twoProbesWithOneNameStopTheServer() {
        CapabilityProbe a = probe("jira", () -> Capabilities.supported("9.12.0", FULL));
        CapabilityProbe b = probe("jira", () -> Capabilities.supported("9.12.0", FULL));

        assertThatThrownBy(() -> runner(new BackendCapabilities(), null, Duration.ofSeconds(1), a, b))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theHealthDetailShowsTheStateButNeverTurnsTheApplicationDown() {
        BackendCapabilities store = new BackendCapabilities();
        CapabilityProbe jira = probe("jira", () -> Capabilities.degraded("10.0.0", BASELINE, "newer"));
        CapabilityProbe gitlab = probe("gitlab", () -> {
            throw new java.io.IOException("down");
        });
        CapabilityProbeRunner runner = runner(store, null, Duration.ofSeconds(2), jira, gitlab);
        runner.runOnce(jira);
        runner.runOnce(gitlab);

        Health health = new BackendCapabilityHealthIndicator(store).health();

        assertThat(health.getStatus().getCode()).isEqualTo("UNAVAILABLE");
        assertThat(health.getDetails()).containsKeys("jira", "gitlab");
        assertThat(new SimpleStatusAggregator().getAggregateStatus(health.getStatus(), Status.UP)).isEqualTo(Status.UP);
    }

    @Test
    void aProbeThatIgnoresTheInterruptIsNotStartedAgainWhileItRuns() {
        BackendCapabilities store = new BackendCapabilities();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean release = new AtomicBoolean();
        CapabilityProbe jira = probe("jira", () -> {
            calls.incrementAndGet();
            while (!release.get()) {
                Thread.onSpinWait();
            }
            return Capabilities.supported("9.12.3", FULL);
        });
        CapabilityProbeRunner runner = runner(store, null, Duration.ofMillis(50), jira);

        runner.runOnce(jira);
        runner.runOnce(jira);
        runner.runOnce(jira);

        assertThat(calls).hasValue(1);
        assertThat(store.of("jira").detail()).isEqualTo("the previous probe is still running");

        release.set(true);
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            runner.runOnce(jira);
            assertThat(store.of("jira").state()).isEqualTo(Capabilities.State.SUPPORTED);
        });
        runner.destroy();
    }

    @Test
    void theRunnerStartsOnceReschedulesAndStopsOnDestroy() throws Exception {
        BackendCapabilities store = new BackendCapabilities();
        AtomicInteger calls = new AtomicInteger();
        CapabilityProbe jira = probe("jira", () -> {
            calls.incrementAndGet();
            return Capabilities.unavailable("down");
        });
        CapabilityProbeRunner runner = new CapabilityProbeRunner(List.of(jira), store, null, Duration.ofSeconds(1),
                Duration.ofMinutes(5), Duration.ofMillis(50));

        runner.start();
        runner.start();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> calls.get() >= 3);
        runner.destroy();
        int afterDestroy = calls.get();
        Thread.sleep(250);

        assertThat(calls.get()).isLessThanOrEqualTo(afterDestroy + 1);
    }
}
