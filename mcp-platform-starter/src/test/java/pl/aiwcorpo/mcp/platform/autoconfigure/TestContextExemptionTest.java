package pl.aiwcorpo.mcp.platform.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ForgedTestFrameworkClass;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;

/** What a Spring test context may skip, and what it may not: the exceptions of the deployment and exclusion checks. */
class TestContextExemptionTest {

    private static final String SECURITY = "pl.aiwcorpo.mcp.platform.security.PlatformSecurityAutoConfiguration";

    private static MockEnvironment prodExcludingSecurity() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.autoconfigure.exclude", SECURITY);
        environment.setActiveProfiles("prod");
        return environment;
    }

    @Test
    void theFrameworkOnTheStackIsRecognizedByItsJar() {
        AtomicBoolean seen = new AtomicBoolean();
        new ApplicationContextRunner().withInitializer(context -> seen.set(DevProfileEnvironment.inSpringTestContext()))
                .run(context -> assertThat(context).hasNotFailed());

        assertThat(seen).isTrue();
        assertThat(DevProfileEnvironment.inSpringTestContext()).isFalse();
    }

    @Test
    void aClassInSpringsTestPackageFromAnotherJarIsNotTheFramework() {
        assertThat(ForgedTestFrameworkClass.run(DevProfileEnvironment::inSpringTestContext)).isFalse();

        assertThatThrownBy(() -> ForgedTestFrameworkClass.run(() -> {
            new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(prodExcludingSecurity(), null);
            return null;
        })).hasMessageContaining("spring.autoconfigure.exclude lists");
    }

    @Test
    void exclusionByPropertyIsRefusedOutsideATestContext() {
        assertThatThrownBy(() -> new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(
                prodExcludingSecurity(), null)).hasMessageContaining("spring.autoconfigure.exclude lists");
    }

    @Test
    void exclusionByPropertyPassesInATestContext() {
        new ApplicationContextRunner().withInitializer(context -> new PlatformDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(prodExcludingSecurity(), null)).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void missingPlatformBeansAreRefusedOutsideATestContext() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.getEnvironment().setActiveProfiles("prod");
            new PlatformPresenceCheck().initialize(context);

            assertThatThrownBy(context::refresh).hasStackTraceContaining("are not defined")
                    .hasStackTraceContaining("platformConfigurationGuard").hasStackTraceContaining("platformMcpServerCustomizer");
        }
    }

    @Test
    void missingPlatformBeansPassInATestContextAndInDev() {
        new ApplicationContextRunner().withInitializer(new PlatformPresenceCheck()).withPropertyValues()
                .run(context -> assertThat(context).hasNotFailed());

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.getEnvironment().setActiveProfiles("dev");
            new PlatformPresenceCheck().initialize(context);
            context.refresh();
        }
    }

    @Test
    void theContainerRefusalSaysHowToWorkInsteadOfOnlyForbidding() {
        String container = DevProfileEnvironment.refusal("a container (Docker or Podman)");
        assertThat(container).contains("remove SPRING_PROFILES_ACTIVE=dev").contains("devcontainer").contains("Codespaces")
                .contains("outside the container").contains("TestGateway").contains("profile other than 'dev'");

        assertThat(DevProfileEnvironment.refusal("a systemd service (INVOCATION_ID is set)")).doesNotContain("devcontainer");
    }
}
