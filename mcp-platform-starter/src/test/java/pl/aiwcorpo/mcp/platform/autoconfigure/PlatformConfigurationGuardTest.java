package pl.aiwcorpo.mcp.platform.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class PlatformConfigurationGuardTest {

    private static McpPlatformProperties valid() {
        McpPlatformProperties props = new McpPlatformProperties();
        props.setServerId("mcp-jira");
        props.getInternalJwt().setJwksUri("https://mcp-gateway.internal:8443/.well-known/jwks.json");
        props.getCredential().setConnectUrl("http://localhost:18090/connections?system={system}");
        return props;
    }

    private static void check(McpPlatformProperties props, MockEnvironment env) {
        new PlatformConfigurationGuard(props, env).afterPropertiesSet();
    }

    @Test
    void theProductionDefaultsStart() {
        assertThatCode(() -> check(valid(), new MockEnvironment())).doesNotThrowAnyException();
    }

    @Test
    void devRelaxationsNeedTheDevProfile() {
        McpPlatformProperties props = valid();
        props.setAuthMode(AuthMode.DEV_PAT);
        props.getInternalJwt().setAllowTestKeys(true);
        props.getInternalJwt().setReplayStore(McpPlatformProperties.ReplayStore.MEMORY);
        props.getMtls().setRequired(false);

        assertThatThrownBy(() -> check(props, new MockEnvironment().withProperty("spring.profiles.active", "prod")))
                .hasMessageContaining("dev-pat").hasMessageContaining("allow-test-keys")
                .hasMessageContaining("replay-store=memory").hasMessageContaining("mtls.required=false");

        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> check(props, dev)).doesNotThrowAnyException();
    }

    @Test
    void requiredSettingsAndContractLimits() {
        McpPlatformProperties props = new McpPlatformProperties();
        props.getInternalJwt().setClockSkew(java.time.Duration.ofSeconds(31));
        props.getInternalJwt().setMaxLifetime(java.time.Duration.ofSeconds(61));

        assertThatThrownBy(() -> check(props, new MockEnvironment()))
                .hasMessageContaining("server-id").hasMessageContaining("jwks-uri").hasMessageContaining("connect-url")
                .hasMessageContaining("clock-skew").hasMessageContaining("max-lifetime");
    }

    @Test
    void settingsOf2xAreRefusedNotIgnored() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("aiwcorpo.mcp.platform.guard.oidc.issuer-uri", "http://localhost:18080/realms/corp")
                .withProperty("aiwcorpo.mcp.platform.pat-header-prefix", "X-PAT-");

        assertThatThrownBy(() -> check(valid(), env)).hasMessageContaining("guard.*").hasMessageContaining("pat-header-prefix");
        assertThat(PlatformConfigurationGuard.DEV_PROFILE).isEqualTo("dev");
    }
}
