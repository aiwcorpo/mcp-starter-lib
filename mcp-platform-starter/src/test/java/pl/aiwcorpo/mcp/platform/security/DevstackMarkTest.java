package pl.aiwcorpo.mcp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

/** The manifest has no field for the environment, so the devstack profile is marked by a gauge. */
class DevstackMarkTest {

    private static double gauge(String profile) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("registry", registry);
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profile);
        new PlatformSecurityAutoConfiguration().devstackMark(env, beans.getBeanProvider(MeterRegistry.class));
        return registry.find("mcp_server_devstack_active").gauge() == null ? 0 : registry.find("mcp_server_devstack_active").gauge().value();
    }

    @Test
    void onlyTheDevstackProfileSetsTheGauge() {
        assertThat(gauge("devstack")).isEqualTo(1.0);
        assertThat(gauge("prod")).isZero();
        assertThat(gauge("dev")).isZero();
    }
}
