package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.util.TestPropertyValues;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;
import pl.aiwcorpo.mcp.platform.observability.PlatformObservabilityAutoConfiguration;
import pl.aiwcorpo.mcp.platform.policy.PlatformPolicyAutoConfiguration;
import pl.aiwcorpo.mcp.platform.policy.PolicyEngine;
import pl.aiwcorpo.mcp.platform.security.PlatformSecurityAutoConfiguration;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/**
 * The configuration guard refuses before anything is pulled: the engine creates the guard in the first line of its bean method
 * (stated in code, not an accident of bean order), so it holds with lazy initialization too. The keys of the internal JWT are
 * fetched lazily and the extension points ({@code GatewayKeys}, {@code JtiReplayStore}) are created after the guard in
 * {@code internalJwtVerifier}; that order has no observable effect to count here. The repository counts the requests; a refused configuration leaves the
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
                .withInitializer(context -> TestPropertyValues.of(properties).applyTo(context))
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
            assertThat(context.getStartupFailure()).hasStackTraceContaining("allow-test-keys=true is allowed only with the 'dev' or the 'devstack' profile");
        });

        assertThat(gateway.main().pointerRequests()).isEqualTo(before);
    }

    /**
     * The acknowledgement counts only from the environment of the process. Here both are properties of the Spring environment (as
     * from an {@code application-devstack.yml} packed in an image): the production wiring must still refuse, and before any request.
     * It fails if the bean of the auto-configuration reads the variable from the Spring environment (the mutation the unit tests,
     * which inject the process environment, cannot see).
     */
    @Test
    void devstackWithBothAcknowledgementsAsPropertiesButNotInTheProcessEnvironmentIsRefused() {
        assumeThat(System.getenv(PlatformConfigurationGuard.DEVSTACK_ACK_ENVIRONMENT))
                .as("the test needs a process without the variable").isNull();
        int before = gateway.main().pointerRequests();

        runner.withPropertyValues("spring.profiles.active=devstack", PlatformConfigurationGuard.DEVSTACK_ACK_PROPERTY + "=true",
                PlatformConfigurationGuard.DEVSTACK_ACK_ENVIRONMENT + "=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("two acknowledgements");
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
