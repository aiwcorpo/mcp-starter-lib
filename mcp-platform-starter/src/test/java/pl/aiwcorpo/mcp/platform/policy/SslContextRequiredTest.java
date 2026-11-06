package pl.aiwcorpo.mcp.platform.policy;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class SslContextRequiredTest {

    private static MockEnvironment profile(String... active) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(active);
        return environment;
    }

    @Test
    void outsideDevThereIsNeverAFallbackToTheJvmDefaultAndNoClientCertificate() {
        for (MockEnvironment environment : new MockEnvironment[] {profile(), profile("prod"), profile("devstack"), profile("dev", "prod")}) {
            assertThatThrownBy(() -> PlatformPolicyAutoConfiguration.requireSslContext(null, environment))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("policy-data.ssl-bundle");
        }
    }

    @Test
    void devMayGoWithoutAndAnyProfileWithAContextIsFine() throws Exception {
        assertThatCode(() -> PlatformPolicyAutoConfiguration.requireSslContext(null, profile("dev"))).doesNotThrowAnyException();
        assertThatCode(() -> PlatformPolicyAutoConfiguration.requireSslContext(SSLContext.getDefault(), profile("prod")))
                .doesNotThrowAnyException();
    }
}
