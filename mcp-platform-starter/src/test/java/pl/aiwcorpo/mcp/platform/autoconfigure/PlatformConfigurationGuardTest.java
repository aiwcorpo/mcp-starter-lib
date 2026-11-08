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
        props.getInternalJwt().setJwksUri("https://mcp.corp.test/.well-known/mcp-jwks.json");
        props.getCredential().setConnectUrl("http://localhost:18090/connections?system={system}");
        props.getManifest().setVersion("1.0.0");
        props.getManifest().setAcceptedCredentialTypes(java.util.List.of("user-secret"));
        return props;
    }

    private static void check(McpPlatformProperties props, MockEnvironment env) {
        new PlatformConfigurationGuard(props, env).afterPropertiesSet();
    }

    private static MockEnvironment production() {
        return new MockEnvironment().withProperty("server.ssl.bundle", "mcp-server");
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
    void theRemovedPolicyDataAndMtlsSettingsStopTheStartWithAPointerToTheNewModel() {
        MockEnvironment policyData = production().withProperty("aiwcorpo.mcp.platform.policy-data.url", "https://mcp-gateway.internal:8443/internal/v1/policy-data");
        assertThatThrownBy(() -> check(valid(), policyData)).hasMessageContaining("policy-data.* (url, tls-name")
                .hasMessageContaining("removed in 4.0.0").hasMessageContaining("POST /.well-known/mcp-policy-data");

        MockEnvironment mtls = production().withProperty("aiwcorpo.mcp.platform.mtls.required", "true");
        assertThatThrownBy(() -> check(valid(), mtls)).hasMessageContaining("mtls.* was removed in 4.0.0");

        MockEnvironment bundle = production().withProperty("aiwcorpo.mcp.platform.internal-jwt.jwks-ssl-bundle", "mcp-internal-ca");
        assertThatThrownBy(() -> check(valid(), bundle)).hasMessageContaining("jwks-ssl-bundle was removed in 4.0.0");

        MockEnvironment clientAuth = production().withProperty("server.ssl.client-auth", "need");
        assertThatThrownBy(() -> check(valid(), clientAuth)).hasMessageContaining("server.ssl.client-auth=need was removed");
    }

    @Test
    void theRemovedPolicyBundleSettingsStopTheStartWithThePointerToTheNewOnes() {
        MockEnvironment env = production().withProperty("aiwcorpo.mcp.platform.policy.main.url", "https://policies.internal/latest.json");

        assertThatThrownBy(() -> check(valid(), env)).hasMessageContaining("policy.* (the signed policy bundles")
                .hasMessageContaining("removed in 4.0.0").hasMessageContaining("pushed by the gateway");
    }

    @Test
    void outsideDevThePortNeedsTlsOrAnExplicitPlainHttp() {
        assertThatThrownBy(() -> check(valid(), new MockEnvironment())).hasMessageContaining("the server port has no TLS")
                .hasMessageContaining("MCP_PLAIN_HTTP=true");
        assertThatThrownBy(() -> check(valid(), production().withProperty("server.ssl.enabled", "false")))
                .hasMessageContaining("the server port has no TLS");
        assertThatCode(() -> check(valid(), new MockEnvironment().withProperty("server.ssl.key-store", "file:/etc/mcp/keystore.p12")))
                .doesNotThrowAnyException();
        assertThatCode(() -> check(valid(), new MockEnvironment().withProperty("MCP_PLAIN_HTTP", "true"))).doesNotThrowAnyException();
    }

    @Test
    void redisAsTheReplayStoreNeedsAHost() {
        McpPlatformProperties redis = valid();
        redis.getInternalJwt().setReplayStore(McpPlatformProperties.ReplayStore.REDIS);

        assertThatThrownBy(() -> check(redis, production())).hasMessageContaining("replay-store=redis needs MCP_REDIS_HOST");
        assertThatCode(() -> check(redis, production().withProperty("spring.data.redis.host", "redis.internal"))).doesNotThrowAnyException();
        McpPlatformProperties memory = valid();
        memory.getInternalJwt().setReplayStore(McpPlatformProperties.ReplayStore.MEMORY);
        assertThatCode(() -> check(memory, production())).doesNotThrowAnyException();
    }

    @Test
    void theInformationalInternalUrlIsOptionalButMustBeAUrlWhenSet() {
        McpPlatformProperties none = valid();
        none.getManifest().setInternalUrl(null);
        assertThatCode(() -> check(none, production())).doesNotThrowAnyException();

        McpPlatformProperties bad = valid();
        bad.getManifest().setInternalUrl("not a url");
        assertThatThrownBy(() -> check(bad, production())).hasMessageContaining("manifest.internal-url is informational and optional");
    }

    @Test
    void aServerIdIsMcpAndASystemOfLowerCaseLettersAndDigitsWhateverItIs() {
        McpPlatformProperties notes = valid();
        notes.setServerId("mcp-notes");
        assertThatCode(() -> check(notes, production())).doesNotThrowAnyException();

        McpPlatformProperties dashed = valid();
        dashed.setServerId("mcp-my-notes");
        assertThatThrownBy(() -> check(dashed, production())).hasMessageContaining("server-id must be set to mcp-<system>");
    }

    @Test
    void devMayUseHttpForTheJwks() {
        DeploymentSignalAssumption.assumeDeveloperMachine();
        McpPlatformProperties props = valid();
        props.getInternalJwt().setJwksUri("http://127.0.0.1:9/jwks.json");

        assertThatCode(() -> check(props, profiles("dev"))).doesNotThrowAnyException();
    }

    @Test
    void theManifestDataMustBeSet() {
        McpPlatformProperties props = valid();
        props.getManifest().setVersion("1.0");
        props.getManifest().setInternalUrl("mcp-jira.internal/mcp");
        props.getManifest().setAcceptedCredentialTypes(java.util.List.of("password"));

        assertThatThrownBy(() -> check(props, production())).hasMessageContaining("manifest.version")
                .hasMessageContaining("manifest.internal-url").hasMessageContaining("'password'");
        props.getManifest().setAcceptedCredentialTypes(java.util.List.of());
        assertThatThrownBy(() -> check(props, production())).hasMessageContaining("accepted-credential-types must list");
    }

    @Test
    void theAcceptedTypesAreOneSetNeverEmptyAndNeverMixingTheServiceAccountWithUsers() {
        McpPlatformProperties users = valid();
        users.getManifest().setAcceptedCredentialTypes(java.util.List.of("user-oauth", "user-secret"));
        assertThatCode(() -> check(users, production())).doesNotThrowAnyException();

        McpPlatformProperties account = valid();
        account.getManifest().setAcceptedCredentialTypes(java.util.List.of("service-account"));
        assertThatCode(() -> check(account, production())).doesNotThrowAnyException();

        McpPlatformProperties mixed = valid();
        mixed.getManifest().setAcceptedCredentialTypes(java.util.List.of("user-secret", "service-account"));
        assertThatThrownBy(() -> check(mixed, production())).hasMessageContaining("mixes service-account");

        McpPlatformProperties none = valid();
        none.getManifest().setCredentialRequired(false);
        none.getManifest().setAcceptedCredentialTypes(java.util.List.of());
        assertThatThrownBy(() -> check(none, production())).hasMessageContaining("also without credential-required");
    }

    @Test
    void theRemovedCredentialTypePropertyStopsTheStartWithTheMigration() {
        MockEnvironment env = production().withProperty("aiwcorpo.mcp.platform.credential-type", "user-secret");

        assertThatThrownBy(() -> check(valid(), env)).hasMessageContaining("credential-type was removed in 3.0.0-beta-7")
                .hasMessageContaining("accepted-credential-types");
    }

    private static MockEnvironment devstack(boolean property) {
        MockEnvironment env = production();
        env.setActiveProfiles("devstack");
        if (property) {
            env.setProperty(PlatformConfigurationGuard.DEVSTACK_ACK_PROPERTY, "true");
        }
        return env;
    }

    /** The guard with the given variables as the environment of the process. */
    private static void checkWithProcessEnvironment(McpPlatformProperties props, MockEnvironment env, boolean variable) {
        new PlatformConfigurationGuard(props, env,
                name -> variable && PlatformConfigurationGuard.DEVSTACK_ACK_ENVIRONMENT.equals(name) ? "true" : null)
                .afterPropertiesSet();
    }

    @Test
    void devstackAcceptsTestKeysOnlyWithBothAcknowledgements() {
        McpPlatformProperties props = valid();
        props.getInternalJwt().setAllowTestKeys(true);

        assertThatCode(() -> checkWithProcessEnvironment(props, devstack(true), true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> checkWithProcessEnvironment(props, devstack(true), false)).hasMessageContaining("two acknowledgements");
        assertThatThrownBy(() -> checkWithProcessEnvironment(props, devstack(false), true)).hasMessageContaining("two acknowledgements");
        assertThatThrownBy(() -> checkWithProcessEnvironment(props, devstack(false), false)).hasMessageContaining("MCP_DEVSTACK_ACKNOWLEDGED");
    }

    @Test
    void theVariableCountsOnlyFromTheEnvironmentOfTheProcessNotFromAnyPropertySource() {
        McpPlatformProperties props = valid();
        props.getInternalJwt().setAllowTestKeys(true);
        // application.yml, the command line, SPRING_APPLICATION_JSON and DynamicPropertySource all end up in the Spring
        // environment under this name; none of them is the environment of the process.
        MockEnvironment env = devstack(true).withProperty(PlatformConfigurationGuard.DEVSTACK_ACK_ENVIRONMENT, "true");

        assertThatThrownBy(() -> checkWithProcessEnvironment(props, env, false)).hasMessageContaining("two acknowledgements");
        assertThatCode(() -> checkWithProcessEnvironment(props, env, true)).doesNotThrowAnyException();
    }

    @Test
    void devstackKeepsEverythingElseOfProduction() {
        McpPlatformProperties props = valid();
        props.getInternalJwt().setAllowTestKeys(true);
        props.getInternalJwt().setJwksUri("http://mcp.corp.test/.well-known/mcp-jwks.json");
        props.setAuthMode(AuthMode.DEV_PAT);

        assertThatThrownBy(() -> checkWithProcessEnvironment(props, devstack(true), true))
                .hasMessageContaining("jwks-uri must be an absolute https URL")
                .hasMessageContaining("dev-pat");
    }

    @Test
    void devstackIsNotCombinedWithAnotherProfileAndOtherProfilesGetNoTestKeys() {
        McpPlatformProperties props = valid();
        MockEnvironment both = devstack(true);
        both.setActiveProfiles("devstack", "prod");
        MockEnvironment prod = production();
        prod.setActiveProfiles("prod");
        McpPlatformProperties withKeys = valid();
        withKeys.getInternalJwt().setAllowTestKeys(true);

        assertThatThrownBy(() -> checkWithProcessEnvironment(props, both, true))
                .hasMessageContaining("'devstack' profile must be the only active profile");
        assertThatThrownBy(() -> checkWithProcessEnvironment(withKeys, prod, true)).hasMessageContaining("'dev' or the 'devstack' profile");
        assertThatThrownBy(() -> check(withKeys, production())).hasMessageContaining("internal-jwt.allow-test-keys");
    }

    @Test
    void devstackMayRunInAContainerWhereDevIsRefused() {
        McpPlatformProperties props = valid();
        props.getInternalJwt().setAllowTestKeys(true);
        MockEnvironment env = devstack(true).withProperty("KUBERNETES_SERVICE_HOST", "10.0.0.1")
                .withProperty("KUBERNETES_SERVICE_PORT", "443");

        assertThatCode(() -> checkWithProcessEnvironment(props, env, true)).doesNotThrowAnyException();
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
    void outsideDevJwksMustBeHttpsAndThePortNeedsTls() {
        DeploymentSignalAssumption.assumeDeveloperMachine();
        McpPlatformProperties props = valid();
        props.getInternalJwt().setJwksUri("http://mcp.corp.test/.well-known/mcp-jwks.json");

        assertThatThrownBy(() -> check(props, new MockEnvironment()))
                .hasMessageContaining("absolute https URL").hasMessageContaining("the server port has no TLS");
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
        DeploymentSignalAssumption.assumeDeveloperMachine();
        McpPlatformProperties props = valid();
        props.setAuthMode(AuthMode.DEV_PAT);
        props.getInternalJwt().setAllowTestKeys(true);

        assertThatThrownBy(() -> check(props, profiles("prod")))
                .hasMessageContaining("dev-pat").hasMessageContaining("allow-test-keys");

        MockEnvironment dev = new MockEnvironment().withProperty("server.address", "127.0.0.1");
        dev.setActiveProfiles("dev");
        assertThatCode(() -> check(props, dev)).doesNotThrowAnyException();
    }

    @Test
    void requiredSettingsAndContractLimits() {
        McpPlatformProperties props = new McpPlatformProperties();
        props.getInternalJwt().setClockSkew(java.time.Duration.ofSeconds(31));
        props.getInternalJwt().setMaxLifetime(java.time.Duration.ofSeconds(61));
        props.getInternalJwt().setMissingTokenEventsPerSecond(0);

        assertThatThrownBy(() -> check(props, production()))
                .hasMessageContaining("server-id").hasMessageContaining("jwks-uri").hasMessageContaining("connect-url")
                .hasMessageContaining("clock-skew").hasMessageContaining("max-lifetime")
                .hasMessageContaining("missing-token-events-per-second");
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
    void devListensOnLoopbackOnly() {
        DeploymentSignalAssumption.assumeDeveloperMachine();
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
        DeploymentSignalAssumption.assumeDeveloperMachine();
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(dev, null);
        assertThat(dev.getProperty("server.address")).isEqualTo("127.0.0.1");

        MockEnvironment prod = new MockEnvironment().withProperty("MCP_PLAIN_HTTP", "true");
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
                Map.of("server.ssl.bundle", "mcp-jira",
                        "aiwcorpo.mcp.platform.internal-jwt.replay-store", "redis")));
        return env;
    }

    @Test
    void devRunsTheServersOwnConfigurationWithoutCertificatesOrRedis() {
        DeploymentSignalAssumption.assumeDeveloperMachine();
        MockEnvironment dev = serverConfigAsInTheReadme("dev");
        new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(dev, null);

        assertThat(dev.getProperty("server.ssl.enabled")).isEqualTo("false");
        assertThat(dev.getProperty("aiwcorpo.mcp.platform.internal-jwt.replay-store")).isEqualTo("memory");
        assertThat(dev.getProperty("server.ssl.bundle")).isEqualTo("mcp-jira");
    }

    @Test
    void theCommandLineStillBeatsTheDevOverrides() {
        DeploymentSignalAssumption.assumeDeveloperMachine();
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
            assertThat(env.getProperty("aiwcorpo.mcp.platform.internal-jwt.replay-store")).isEqualTo("redis");
        }
    }

    @Test
    void aSeparateManagementServerInDevMustListenOnLoopbackToo() {
        DeploymentSignalAssumption.assumeDeveloperMachine();
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
        DeploymentSignalAssumption.assumeDeveloperMachine();
        MockEnvironment prodInKubernetes = serverConfigAsInTheReadme("prod").withProperty("spring.main.cloud-platform", "kubernetes");
        assertThatCode(() -> new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(prodInKubernetes, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> new PlatformDefaultsEnvironmentPostProcessor()
                .postProcessEnvironment(serverConfigAsInTheReadme("dev"), null)).doesNotThrowAnyException();
    }

    @Test
    void theOverridesThatChangedTheServersConfigurationAreNamedInTheLog() {
        DeploymentSignalAssumption.assumeDeveloperMachine();
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(PlatformConfigurationGuard.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            MockEnvironment dev = serverConfigAsInTheReadme("dev");
            dev.setProperty("server.address", "127.0.0.1");
            new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(dev, null);
            check(valid(), dev);
            String line = pl.aiwcorpo.mcp.platform.CapturedLogs.snapshot(appender).stream()
                    .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.contains("dev_profile_overrides")).findFirst().orElseThrow();
            assertThat(line).contains("replay-store");
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static DevProfileEnvironment.Host host(Map<String, String> variables, Map<String, String> files) {
        return new DevProfileEnvironment.Host() {
            @Override
            public String variable(String name) {
                return variables.get(name);
            }

            @Override
            public boolean exists(String path) {
                return files.containsKey(path);
            }

            @Override
            public String read(String path) {
                return files.getOrDefault(path, "");
            }
        };
    }

    private static java.util.Optional<String> signal(MockEnvironment environment, DevProfileEnvironment.Host host,
            boolean testContext) {
        return DevProfileEnvironment.deploymentSignal(environment, host, () -> testContext);
    }

    @Test
    void cloudPlatformNoneIsNotADeployment() {
        DeploymentSignalAssumption.assumeDeveloperMachine();
        MockEnvironment none = new MockEnvironment().withProperty("spring.main.cloud-platform", "none");
        assertThat(signal(none, host(Map.of(), Map.of()), false)).isEmpty();
        MockEnvironment dev = serverConfigAsInTheReadme("dev").withProperty("spring.main.cloud-platform", "none");
        assertThatCode(() -> new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(dev, null))
                .doesNotThrowAnyException();
    }

    @Test
    void aContainerIsADeploymentWhateverTheEnvironmentPropertiesSay() {
        MockEnvironment env = new MockEnvironment();
        assertThat(signal(env, host(Map.of(), Map.of("/.dockerenv", "")), false)).hasValueSatisfying(
                s -> assertThat(s).contains("container"));
        assertThat(signal(env, host(Map.of(), Map.of("/run/.containerenv", "")), false)).isPresent();
        assertThat(signal(env, host(Map.of("container", "podman"), Map.of()), false)).isPresent();
        assertThat(signal(env, host(Map.of(), Map.of("/proc/self/cgroup", "12:cpu:/docker/4f2a9c\n")), false)).isPresent();
        assertThat(signal(env, host(Map.of(), Map.of("/proc/1/cgroup", "0::/kubepods.slice/kubepods-burstable.slice/x")), false))
                .isPresent();
        assertThat(signal(env, host(Map.of(), Map.of("/proc/self/cgroup", "0::/user.slice/user-501.slice/session-3.scope")), false))
                .isEmpty();
    }

    @Test
    void theProcessVariablesCountEvenWhenAPropertyHidesThem() {
        MockEnvironment shadowed = new MockEnvironment().withProperty("INVOCATION_ID", "")
                .withProperty("spring.main.cloud-platform", "off");
        assertThat(signal(shadowed, host(Map.of("INVOCATION_ID", "7f3a9c1e5b2d4e8f"), Map.of()), false))
                .hasValueSatisfying(s -> assertThat(s).contains("systemd"));
        assertThat(signal(shadowed, host(Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1", "KUBERNETES_SERVICE_PORT", "443"),
                Map.of()), false)).hasValueSatisfying(s -> assertThat(s).contains("Kubernetes"));
        assertThat(signal(shadowed, host(Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1"), Map.of()), false)).isEmpty();
    }

    @Test
    void aSpringTestContextIsNeverADeploymentButOtherCodeOnAPodIs() {
        DevProfileEnvironment.Host pod = host(Map.of("KUBERNETES_SERVICE_HOST", "10.0.0.1", "KUBERNETES_SERVICE_PORT", "443",
                "INVOCATION_ID", "7f3a9c1e5b2d4e8f"), Map.of("/.dockerenv", ""));
        MockEnvironment env = new MockEnvironment().withProperty("spring.main.cloud-platform", "kubernetes");

        assertThat(signal(env, pod, true)).isEmpty();
        assertThat(signal(env, pod, false)).isPresent();
        assertThat(DevProfileEnvironment.inSpringTestContext()).isFalse();
    }

    @Test
    void theManagementPortIsComparedAsANumber() {
        DeploymentSignalAssumption.assumeDeveloperMachine();
        MockEnvironment randomBoth = profiles("dev").withProperty("server.port", "0").withProperty("management.server.port", "0");
        assertThatThrownBy(() -> check(valid(), randomBoth)).hasMessageContaining("management.server.address");

        MockEnvironment padded = profiles("dev").withProperty("server.port", "8080").withProperty("management.server.port", " 8080");
        assertThatCode(() -> check(valid(), padded)).doesNotThrowAnyException();

        MockEnvironment disabled = profiles("dev").withProperty("server.port", "18600").withProperty("management.server.port", "-1");
        assertThatCode(() -> check(valid(), disabled)).doesNotThrowAnyException();

        MockEnvironment notANumber = profiles("dev").withProperty("management.server.port", "http");
        assertThatThrownBy(() -> check(valid(), notANumber)).hasMessageContaining("management.server.address");

        MockEnvironment prodRandom = production().withProperty("server.port", "0").withProperty("management.server.port", "0");
        assertThatThrownBy(() -> check(valid(), prodRandom)).hasMessageContaining("management.server.port must be unset");
        MockEnvironment prodDisabled = production().withProperty("management.server.port", "-1");
        assertThatCode(() -> check(valid(), prodDisabled)).doesNotThrowAnyException();
    }

    @Test
    void excludingThePlatformsAutoConfigurationsStopsTheServerInEveryProfile() {
        String guard = "pl.aiwcorpo.mcp.platform.security.PlatformSecurityAutoConfiguration";
        MockEnvironment prod = serverConfigAsInTheReadme("prod").withProperty("spring.autoconfigure.exclude",
                "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration, " + guard);
        assertThatThrownBy(() -> new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(prod, null))
                .hasMessageContaining("spring.autoconfigure.exclude").hasMessageContaining(guard);

        MockEnvironment indexed = serverConfigAsInTheReadme("prod").withProperty("spring.autoconfigure.exclude[0]", guard);
        assertThatThrownBy(() -> new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(indexed, null))
                .hasMessageContaining(guard);

        MockEnvironment noProfile = new MockEnvironment().withProperty("spring.autoconfigure.exclude", guard);
        assertThatThrownBy(() -> new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(noProfile, null))
                .hasMessageContaining(guard);

        MockEnvironment unrelated = serverConfigAsInTheReadme("prod").withProperty("spring.autoconfigure.exclude",
                "org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration");
        assertThatCode(() -> new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(unrelated, null))
                .doesNotThrowAnyException();
        MockEnvironment dev = serverConfigAsInTheReadme("dev").withProperty("spring.autoconfigure.exclude", guard);
        assertThatThrownBy(() -> new PlatformDefaultsEnvironmentPostProcessor().postProcessEnvironment(dev, null))
                .hasMessageContaining(guard).hasMessageContaining("'dev' profile either");
    }
}
