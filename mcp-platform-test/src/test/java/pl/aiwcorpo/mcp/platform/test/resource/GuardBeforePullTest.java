package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import pl.aiwcorpo.mcp.platform.observability.PlatformObservabilityAutoConfiguration;
import pl.aiwcorpo.mcp.platform.policy.PlatformPolicyAutoConfiguration;
import pl.aiwcorpo.mcp.platform.policy.PolicyEngine;
import pl.aiwcorpo.mcp.platform.security.PlatformSecurityAutoConfiguration;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/**
 * The configuration guard refuses before anything is pulled: the bean order is stated ({@code @DependsOn}), not an accident of
 * parameters, so it holds with lazy initialization too. The repository counts the requests; a refused configuration leaves the
 * count where it was, and the same configuration under {@code dev} (the control) pulls.
 */
class GuardBeforePullTest {

    private TestGateway gateway;
    private ApplicationContextRunner runner;

    @BeforeEach
    void start() {
        gateway = TestGateway.start("mcp-jira");
        Map<String, String> properties = gateway.serverProperties();
        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformSecurityAutoConfiguration.class,
                        PlatformObservabilityAutoConfiguration.class, PlatformPolicyAutoConfiguration.class))
                .withInitializer(context -> org.springframework.boot.test.util.TestPropertyValues.of(properties).applyTo(context))
                .withPropertyValues("server.address=127.0.0.1", "server.ssl.client-auth=need");
    }

    @AfterEach
    void stop() {
        gateway.close();
    }

    @Test
    void testKeysOutsideDevAndDevstackAreRefusedBeforeTheFirstRequestToTheRepository() {
        int before = gateway.main().pointerRequests();

        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("allow-test-keys=true is allowed only with the 'dev' or the 'devstack' profile");
        });

        assertThat(gateway.main().pointerRequests()).isEqualTo(before);
    }

    @Test
    void theSameHoldsWithLazyInitialization() {
        int before = gateway.main().pointerRequests();

        runner.withPropertyValues("spring.profiles.active=prod", "spring.main.lazy-initialization=true").run(context -> {
            assertThat(context).hasFailed();
        });

        assertThat(gateway.main().pointerRequests()).isEqualTo(before);
    }

    @Test
    void underDevTheEngineIsStartedAndPullsAsTheControl() {
        int before = gateway.main().pointerRequests();

        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PolicyEngine.class);
        });

        assertThat(gateway.main().pointerRequests()).isGreaterThan(before);
    }
}
