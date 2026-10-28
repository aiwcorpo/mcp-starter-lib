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
    void onlyAJarFileNamedLikeTheFrameworkCounts() {
        for (String location : new String[] {"file:/home/dev/.m2/org/springframework/spring-test/7.0.8/spring-test-7.0.8.jar",
                "jar:file:/opt/app/lib/spring-boot-test-4.1.0.jar!/",
                "jar:nested:/app/app.jar/!BOOT-INF/lib/spring-test-7.0.8.jar!/"}) {
            assertThat(DevProfileEnvironment.testFrameworkLocation(location)).as(location).isTrue();
        }
        for (String location : new String[] {"file:/opt/srv/spring-test-1.jar/", "file:/opt/srv/spring-test-1.jar/classes/",
                "file:/opt/srv/spring-test-1.jar/x/spring-boot-test-1.jar/", "file:/home/dev/spring-boot-test-autoconfigure-4.1.0.jar",
                "file:/usr/share/java/spring-test.jar", "file:/cache/processed_spring-test-7.0.8.jar"}) {
            assertThat(DevProfileEnvironment.testFrameworkLocation(location)).as(location).isFalse();
        }
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
    void missingPlatformBeansPassInATestContextButNotInDev() {
        new ApplicationContextRunner().withInitializer(new PlatformPresenceCheck()).withPropertyValues()
                .run(context -> assertThat(context).hasNotFailed());

        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.getEnvironment().setActiveProfiles("dev");
            new PlatformPresenceCheck().initialize(context);
            assertThatThrownBy(context::refresh).hasMessageContaining("are not defined")
                    .hasMessageContaining("platformConfigurationGuard");
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
