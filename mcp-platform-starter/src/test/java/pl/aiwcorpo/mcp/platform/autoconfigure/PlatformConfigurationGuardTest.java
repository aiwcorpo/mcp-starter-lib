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

    private static MockEnvironment production() {
        return new MockEnvironment().withProperty("server.ssl.client-auth", "need");
    }

    private static MockEnvironment profiles(String... active) {
        MockEnvironment env = production();
        env.setActiveProfiles(active);
        return env;
    }

    @Test
    void theProductionDefaultsStart() {
        assertThatCode(() -> check(valid(), production())).doesNotThrowAnyException();
    }

    @Test
    void devNextToAnotherProfileStopsTheServer() {
        assertThatThrownBy(() -> check(valid(), profiles("prod", "dev")))
                .hasMessageContaining("'dev' profile must be the only active profile");
    }

    @Test
    void devAsDefaultProfileGrantsNoRelaxation() {
        McpPlatformProperties props = valid();
        props.getInternalJwt().setAllowTestKeys(true);
        MockEnvironment env = production();
        env.setDefaultProfiles("dev");

        assertThatThrownBy(() -> check(props, env)).hasMessageContaining("allow-test-keys");
    }

    @Test
    void onlyTheHealthProbesMayBeUnauthenticated() {
        McpPlatformProperties props = valid();
        props.getInternalJwt().setUnauthenticatedPaths(java.util.List.of("/actuator/health", "/mcp"));

        assertThatThrownBy(() -> check(props, profiles("dev"))).hasMessageContaining("not '/mcp'");
    }

    @Test
    void outsideDevJwksMustBeHttpsAndClientAuthNeed() {
        McpPlatformProperties props = valid();
        props.getInternalJwt().setJwksUri("http://mcp-gateway.internal:8080/.well-known/jwks.json");

        assertThatThrownBy(() -> check(props, new MockEnvironment()))
                .hasMessageContaining("absolute https URL").hasMessageContaining("client-auth=need");
        assertThatCode(() -> check(props, profiles("dev"))).doesNotThrowAnyException();
    }

    @Test
    void aSeparateManagementPortOrAnotherProtocolStopsTheServer() {
        MockEnvironment env = production().withProperty("server.port", "8443")
                .withProperty("management.server.port", "9090")
                .withProperty("spring.ai.mcp.server.protocol", "STATELESS");

        assertThatThrownBy(() -> check(valid(), env))
                .hasMessageContaining("management.server.port").hasMessageContaining("STREAMABLE");
    }

    @Test
    void devRelaxationsNeedTheDevProfile() {
        McpPlatformProperties props = valid();
        props.setAuthMode(AuthMode.DEV_PAT);
        props.getInternalJwt().setAllowTestKeys(true);
        props.getInternalJwt().setReplayStore(McpPlatformProperties.ReplayStore.MEMORY);
        props.getMtls().setRequired(false);

        assertThatThrownBy(() -> check(props, profiles("prod")))
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

        assertThatThrownBy(() -> check(props, production()))
                .hasMessageContaining("server-id").hasMessageContaining("jwks-uri").hasMessageContaining("connect-url")
                .hasMessageContaining("clock-skew").hasMessageContaining("max-lifetime");
    }

    @Test
    void settingsOf2xAreRefusedNotIgnored() {
        MockEnvironment env = production()
                .withProperty("aiwcorpo.mcp.platform.guard.oidc.issuer-uri", "http://localhost:18080/realms/corp")
                .withProperty("aiwcorpo.mcp.platform.pat-header-prefix", "X-PAT-");

        assertThatThrownBy(() -> check(valid(), env)).hasMessageContaining("guard.*").hasMessageContaining("pat-header-prefix");
        assertThat(PlatformConfigurationGuard.DEV_PROFILE).isEqualTo("dev");
    }
}
