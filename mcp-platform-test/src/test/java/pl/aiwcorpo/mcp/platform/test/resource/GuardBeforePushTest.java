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
 * The configuration guard refuses before the policy engine exists: the engine creates the guard in the first line of its bean method
 * (stated in code, not an accident of bean order), so it holds with lazy initialization too. The failure is the guard's message, and no
 * {@code PolicyEngine} bean is left behind; the same configuration under {@code dev} (the control) starts an engine that holds no data until
 * the gateway pushes some.
 */
class GuardBeforePushTest {

    /** The application of the context: the policies must come from the classpath root of its code (here the test classes). */
    @org.springframework.boot.SpringBootConfiguration
    static class Application {
    }

    private TestGateway gateway;
    private ApplicationContextRunner runner;

    @BeforeEach
    void start() {
        gateway = TestGateway.start("mcp-jira");
        Map<String, String> properties = gateway.serverProperties();
        runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PlatformSecurityAutoConfiguration.class,
                        PlatformObservabilityAutoConfiguration.class, PlatformPolicyAutoConfiguration.class))
                .withUserConfiguration(Application.class)
                .withInitializer(context -> TestPropertyValues.of(properties).applyTo(context))
                .withPropertyValues("server.address=127.0.0.1", "server.ssl.bundle=mcp-server");
    }

    @AfterEach
    void stop() {
        gateway.close();
    }

    @Test
    void testKeysOutsideDevAndDevstackAreRefusedBeforeAnEngineExists() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("allow-test-keys=true is allowed only with the 'dev' or the 'devstack' profile");
        });
    }

    @Test
    void theSameHoldsWithLazyInitialization() {
        runner.withPropertyValues("spring.profiles.active=prod", "spring.main.lazy-initialization=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("allow-test-keys=true is allowed only with the 'dev' or the 'devstack' profile");
        });
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

        runner.withPropertyValues("spring.profiles.active=devstack", PlatformConfigurationGuard.DEVSTACK_ACK_PROPERTY + "=true",
                PlatformConfigurationGuard.DEVSTACK_ACK_ENVIRONMENT + "=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("two acknowledgements");
        });
    }

    @Test
    void underDevTheEngineIsStartedAndHoldsNothingUntilThePushAsTheControl() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PolicyEngine.class);
            assertThat(context.getBean(PolicyEngine.class).revision()).isEmpty();
        });
    }
}
