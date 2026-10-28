package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformPresenceCheck;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;
import pl.aiwcorpo.mcp.platform.test.excluding.ExcludingServer;
import pl.aiwcorpo.mcp.platform.test.own.OwnFilterServer;

/**
 * {@link DevProfileGuardAssertions#assertServerIsGuarded} on real application contexts of the {@code dev} profile:
 * the one the platform builds passes, the one of a server that excludes the platform's security configuration does
 * not (with the address of a network, which the guard would have refused had it existed), and a network address
 * fails even when every bean is there.
 */
class ServerGuardedAssertionTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");

    @AfterAll
    static void stop() {
        GATEWAY.close();
    }

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    @Nested
    @SpringBootTest(classes = ProbeServer.class, properties = "spring.ai.mcp.server.name=guarded-assertion-test")
    @ActiveProfiles("dev")
    class FullServer {

        @Autowired
        ApplicationContext context;

        @Test
        void aGuardedServerOnLoopbackPasses() {
            assertThatCode(() -> DevProfileGuardAssertions.assertServerIsGuarded(context)).doesNotThrowAnyException();
        }
    }

    @Nested
    @SpringBootTest(classes = ExcludingServer.class,
            properties = {"spring.ai.mcp.server.name=guarded-assertion-test", "server.address=0.0.0.0"})
    @ActiveProfiles("dev")
    class ExcludedGuardOnANetworkAddress {

        @Autowired
        ApplicationContext context;

        @Test
        void theContextStartsAndTheAssertionFails() {
            assertThatThrownBy(() -> DevProfileGuardAssertions.assertServerIsGuarded(context))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("platformConfigurationGuard");
        }
    }

    @Test
    void aNetworkAddressFailsEvenWhenEveryPlatformBeanIsThere() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            PlatformPresenceCheck.REQUIRED_BEANS.forEach(name -> context.registerBean(name, Object.class));
            context.getEnvironment().setActiveProfiles("dev");
            context.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("address", Map.of("server.address", "0.0.0.0")));
            context.refresh();

            assertThatThrownBy(() -> DevProfileGuardAssertions.assertServerIsGuarded(context))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("0.0.0.0");
        }
    }

    @Test
    void aContextOutsideDevFailsTheAssertionEvenWithEveryBeanAndALoopbackAddress() {
        for (String[] profiles : new String[][] {{"prod"}, {}, {"dev", "prod"}}) {
            try (GenericApplicationContext context = new GenericApplicationContext()) {
                PlatformPresenceCheck.REQUIRED_BEANS.forEach(name -> context.registerBean(name, Object.class));
                context.getEnvironment().setActiveProfiles(profiles);
                context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("address", Map.of("server.address", "127.0.0.1")));
                context.refresh();

                assertThatThrownBy(() -> DevProfileGuardAssertions.assertServerIsGuarded(context))
                        .isInstanceOf(AssertionError.class).hasMessageContaining("'dev' profile only");
            }
        }
    }

    /** With lazy initialization a defined bean may never have been created, so its checks never ran. */
    @Test
    void aBeanThatIsDefinedButFailsWhenCreatedFailsTheAssertion() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.getDefaultListableBeanFactory().setAllowBeanDefinitionOverriding(true);
            PlatformPresenceCheck.REQUIRED_BEANS.forEach(name -> context.registerBean(name, Object.class));
            context.registerBean("platformConfigurationGuard", Object.class, () -> {
                throw new IllegalStateException("server.address must be a loopback address");
            }, bd -> bd.setLazyInit(true));
            context.getEnvironment().setActiveProfiles("dev");
            context.refresh();

            assertThatThrownBy(() -> DevProfileGuardAssertions.assertServerIsGuarded(context))
                    .isInstanceOf(AssertionError.class).hasMessageContaining("platformConfigurationGuard")
                    .hasMessageContaining("cannot be created");
        }
    }

    @Nested
    @SpringBootTest(classes = OwnFilterServer.class,
            properties = {"spring.ai.mcp.server.name=guarded-assertion-test", "spring.main.lazy-initialization=true"})
    @ActiveProfiles("dev")
    class LazyServerWithItsOwnCredentialFilter {

        @Autowired
        ApplicationContext context;

        @Test
        void theGuardExistsAndTheAssertionPasses() {
            assertThatCode(() -> DevProfileGuardAssertions.assertServerIsGuarded(context)).doesNotThrowAnyException();
        }
    }
}
