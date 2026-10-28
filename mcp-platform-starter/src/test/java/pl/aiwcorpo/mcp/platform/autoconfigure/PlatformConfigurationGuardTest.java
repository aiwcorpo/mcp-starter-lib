package pl.aiwcorpo.mcp.platform.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
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
        MockEnvironment env = production().withProperty("server.address", "127.0.0.1");
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

        MockEnvironment dev = new MockEnvironment().withProperty("server.address", "127.0.0.1");
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

    @Test
    void aSystemOutsideTheContractIsRefusedOutsideDevAndOnlyWarnedInDev() {
        McpPlatformProperties props = valid();
        props.setServerId("mcp-example");

        assertThatThrownBy(() -> check(props, production())).hasMessageContaining("system 'example' is not a SystemId");
        assertThatCode(() -> check(props, profiles("dev"))).doesNotThrowAnyException();
        assertThat(PlatformConfigurationGuard.SYSTEM_IDS)
                .containsExactlyInAnyOrder("jira", "confluence", "gitlab", "servicedesk", "opensearch");
    }

    @Test
    void devListensOnLoopbackOnly() {
        for (String address : new String[] {"127.0.0.1", "::1", "localhost"}) {
            MockEnvironment env = profiles("dev").withProperty("server.address", address);
            assertThatCode(() -> check(valid(), env)).as(address).doesNotThrowAnyException();
        }
        for (String address : new String[] {"0.0.0.0", "10.0.0.5", "mcp-jira.internal", ""}) {
            MockEnvironment env = profiles("dev").withProperty("server.address", address);
            assertThatThrownBy(() -> check(valid(), env)).as(address).hasMessageContaining("loopback");
        }
        MockEnvironment unset = new MockEnvironment();
        unset.setActiveProfiles("dev");
        assertThatThrownBy(() -> check(valid(), unset)).hasMessageContaining("loopback");
    }

    @Test
    void theStarterDefaultsDevToLoopbackAndNothingElse() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(dev, null);
        assertThat(dev.getProperty("server.address")).isEqualTo("127.0.0.1");

        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(prod, null);
        assertThat(prod.getProperty("server.address")).isNull();
    }

    private static MockEnvironment serverConfigAsInTheReadme(String... activeProfiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfiles);
        env.getPropertySources().addFirst(new MapPropertySource("commandLineArgs", Map.of()));
        env.getPropertySources().addLast(new MapPropertySource(
                "Config resource 'class path resource [application.yml]' via location 'optional:classpath:/'",
                Map.of("server.ssl.bundle", "mcp-jira", "server.ssl.client-auth", "need",
                        "aiwcorpo.mcp.platform.mtls.required", "true",
                        "aiwcorpo.mcp.platform.internal-jwt.replay-store", "redis",
                        "aiwcorpo.mcp.platform.internal-jwt.jwks-ssl-bundle", "mcp-internal-ca")));
        return env;
    }

    @Test
    void devRunsTheServersOwnConfigurationWithoutCertificatesRedisOrInternalCa() {
        MockEnvironment dev = serverConfigAsInTheReadme("dev");
        new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(dev, null);

        assertThat(dev.getProperty("server.ssl.enabled")).isEqualTo("false");
        assertThat(dev.getProperty("aiwcorpo.mcp.platform.mtls.required")).isEqualTo("false");
        assertThat(dev.getProperty("aiwcorpo.mcp.platform.internal-jwt.replay-store")).isEqualTo("memory");
        assertThat(dev.getProperty("aiwcorpo.mcp.platform.internal-jwt.jwks-ssl-bundle")).isEmpty();
        assertThat(dev.getProperty("server.ssl.bundle")).isEqualTo("mcp-jira");
    }

    @Test
    void theCommandLineStillBeatsTheDevOverrides() {
        MockEnvironment dev = serverConfigAsInTheReadme("dev");
        dev.getPropertySources().remove("commandLineArgs");
        dev.getPropertySources().addFirst(new MapPropertySource("commandLineArgs",
                Map.of("aiwcorpo.mcp.platform.internal-jwt.replay-store", "redis")));
        new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(dev, null);

        assertThat(dev.getProperty("aiwcorpo.mcp.platform.internal-jwt.replay-store")).isEqualTo("redis");
    }

    @Test
    void outsideTheOneAndOnlyDevProfileNothingIsOverridden() {
        for (String[] profiles : new String[][] {{"prod"}, {"dev", "prod"}, {}}) {
            MockEnvironment env = serverConfigAsInTheReadme(profiles);
            new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(env, null);

            assertThat(env.getProperty("server.ssl.enabled")).as(String.join(",", profiles)).isNull();
            assertThat(env.getProperty("aiwcorpo.mcp.platform.mtls.required")).isEqualTo("true");
            assertThat(env.getProperty("aiwcorpo.mcp.platform.internal-jwt.jwks-ssl-bundle")).isEqualTo("mcp-internal-ca");
        }
    }

    @Test
    void aSeparateManagementServerInDevMustListenOnLoopbackToo() {
        MockEnvironment lan = profiles("dev").withProperty("server.port", "18600")
                .withProperty("management.server.port", "18601");
        assertThatThrownBy(() -> check(valid(), lan)).hasMessageContaining("management.server.address");

        MockEnvironment elsewhere = profiles("dev").withProperty("server.port", "18600")
                .withProperty("management.server.port", "18601").withProperty("management.server.address", "0.0.0.0");
        assertThatThrownBy(() -> check(valid(), elsewhere)).hasMessageContaining("'0.0.0.0'");

        MockEnvironment loopback = profiles("dev").withProperty("server.port", "18600")
                .withProperty("management.server.port", "18601").withProperty("management.server.address", "127.0.0.1");
        assertThatCode(() -> check(valid(), loopback)).doesNotThrowAnyException();

        MockEnvironment sharedPort = profiles("dev").withProperty("server.port", "18600")
                .withProperty("management.server.port", "18600");
        assertThatCode(() -> check(valid(), sharedPort)).doesNotThrowAnyException();
    }

    @Test
    void devInADeploymentIsRefusedBeforeAnySocketIsOpened() {
        MockEnvironment kubernetes = serverConfigAsInTheReadme("dev").withProperty("spring.main.cloud-platform", "kubernetes");
        assertThatThrownBy(() -> new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(kubernetes, null))
                .hasMessageContaining("cloud platform").hasMessageContaining("remove SPRING_PROFILES_ACTIVE=dev");
        assertThat(kubernetes.getProperty("server.ssl.enabled")).isNull();

        MockEnvironment systemd = serverConfigAsInTheReadme("dev").withProperty("INVOCATION_ID", "7f3a9c1e5b2d4e8f");
        assertThatThrownBy(() -> new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(systemd, null))
                .hasMessageContaining("systemd");
        assertThatThrownBy(() -> check(valid(), profiles("dev").withProperty("INVOCATION_ID", "7f3a9c1e5b2d4e8f")))
                .hasMessageContaining("systemd");
    }

    @Test
    void theDeploymentCheckDoesNotTouchProductionProfilesOrADevelopersMachine() {
        MockEnvironment prodInKubernetes = serverConfigAsInTheReadme("prod").withProperty("spring.main.cloud-platform", "kubernetes");
        assertThatCode(() -> new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(prodInKubernetes, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> new PlatformDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(serverConfigAsInTheReadme("dev"), null)).doesNotThrowAnyException();
    }

    @Test
    void theOverridesThatChangedTheServersConfigurationAreNamedInTheLog() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(PlatformDefaultsEnvironmentPostProcessor.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(serverConfigAsInTheReadme("dev"), null);
            String line = pl.aiwcorpo.mcp.platform.CapturedLogs.snapshot(appender).stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("dev_profile_overrides")).findFirst().orElseThrow();
            assertThat(line).contains("aiwcorpo.mcp.platform.mtls.required").contains("replay-store")
                    .contains("jwks-ssl-bundle");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
