package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stops the server at startup when its configuration would weaken the gateway model.
 *
 * <ul>
 *   <li>{@code server-id}, {@code internal-jwt.jwks-uri} and {@code credential.connect-url} are set.</li>
 *   <li>The development relaxations ({@code auth-mode=dev-pat}, {@code internal-jwt.allow-test-keys},
 *       {@code internal-jwt.replay-store=memory}, {@code mtls.required=false}, plain-http JWKS, a separate
 *       management port) only when {@code dev} is the one and only active profile; {@code dev} next to another
 *       profile, through a profile group or as a default profile does not count and, when active with others,
 *       stops the server.</li>
 *   <li>Outside {@code dev}: {@code server.ssl.client-auth=need}, https JWKS, no separate management port.</li>
 *   <li>Unauthenticated paths only from {@link #ALLOWED_UNAUTHENTICATED_PATHS}; MCP transport STREAMABLE.</li>
 *   <li>Clock skew at most 30 s and token lifetime at most 60 s (C2).</li>
 *   <li>No 2.x settings ({@code guard.*}, {@code pat-header-prefix}): left in place they would be ignored
 *       while the operator believes they still protect the server.</li>
 * </ul>
 */
public class PlatformConfigurationGuard implements InitializingBean {

    /** The profile under which development relaxations are allowed. */
    public static final String DEV_PROFILE = "dev";

    /**
     * The profile of a shared development stack (containers of mcp-platform-dev): everything is as in production (TLS, mTLS,
     * Redis, https, state directory) except that signing keys named {@code test-*} and the published test keys are accepted.
     * Allowed in a container, but only with the explicit acknowledgement below, and it is never combined with another profile.
     */
    public static final String DEVSTACK_PROFILE = "devstack";

    /**
     * Property and environment variable that must both be {@code true} for the {@code devstack} profile. The variable is read from
     * the environment of the process only: a property of that name in {@code application.yml}, on the command line or in
     * {@code SPRING_APPLICATION_JSON} does not count, so a configuration file packed in the image cannot carry the acknowledgement. The
     * property may come from anywhere (the same block of the stack's {@code environment:} sets both).
     */
    public static final String DEVSTACK_ACK_PROPERTY = "aiwcorpo.mcp.platform.devstack.acknowledged";
    public static final String DEVSTACK_ACK_ENVIRONMENT = "MCP_DEVSTACK_ACKNOWLEDGED";

    /** True when {@code devstack} is the only active profile; with {@code allowTestKeys} it makes dev mode (CC-137). */
    public static boolean devstackProfile(Environment environment) {
        return List.of(environment.getActiveProfiles()).equals(List.of(DEVSTACK_PROFILE));
    }

    /** The only paths that may be served without the internal JWT (health probes of the orchestrator). */
    public static final Set<String> ALLOWED_UNAUTHENTICATED_PATHS =
            Set.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");

    private static final Logger LOG = LoggerFactory.getLogger(PlatformConfigurationGuard.class);

    /** {@code common.schema.json#/$defs/SystemId} from the contracts jar on the classpath. */
    static final Set<String> SYSTEM_IDS = systemIds();

    /** {@code common.schema.json#/$defs/ComponentId}. */
    private static final Pattern COMPONENT_ID = Pattern.compile("^mcp-[a-z0-9]+(-[a-z0-9]+)*$");

    private final McpPlatformProperties properties;
    private final Environment environment;
    private final Function<String, String> processEnvironment;

    public PlatformConfigurationGuard(McpPlatformProperties properties, Environment environment) {
        this(properties, environment, System::getenv);
    }

    /**
     * @param processEnvironment the environment variables of the process ({@code System::getenv}); injectable for tests. The
     *                           {@code devstack} acknowledgement is read from here and from nowhere else.
     */
    public PlatformConfigurationGuard(McpPlatformProperties properties, Environment environment,
                                      Function<String, String> processEnvironment) {
        this.properties = properties;
        this.environment = environment;
        this.processEnvironment = processEnvironment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> problems = new ArrayList<>();
        McpPlatformProperties.InternalJwt jwt = properties.getInternalJwt();

        String serverId = properties.getServerId();
        if (serverId == null || !COMPONENT_ID.matcher(serverId).matches()) {
            problems.add("aiwcorpo.mcp.platform.server-id must be set to mcp-<system> (the aud of the internal JWT)");
        }
        List<String> active = List.of(environment.getActiveProfiles());
        boolean dev = active.equals(List.of(DEV_PROFILE));
        if (active.contains(DEV_PROFILE) && !dev) {
            problems.add("the 'dev' profile must be the only active profile (active: " + active + "): its relaxations "
                    + "must not ride along with another profile");
        }

        boolean devstack = active.equals(List.of(DEVSTACK_PROFILE));
        if (active.contains(DEVSTACK_PROFILE) && !devstack) {
            problems.add("the 'devstack' profile must be the only active profile (active: " + active + ")");
        }
        if (devstack) {
            boolean acknowledged = "true".equalsIgnoreCase(environment.getProperty(DEVSTACK_ACK_PROPERTY, ""))
                    && "true".equalsIgnoreCase(processEnvironment.apply(DEVSTACK_ACK_ENVIRONMENT));
            if (!acknowledged) {
                problems.add("the 'devstack' profile accepts signing keys named test-* and needs two acknowledgements, so that a "
                        + "copied configuration does not carry it to production: " + DEVSTACK_ACK_PROPERTY + "=true and the "
                        + "environment variable " + DEVSTACK_ACK_ENVIRONMENT + "=true");
            } else {
                // The manifest says environment: dev (CC-138); every start says it as well, and a gauge shows it.
                LOG.warn("event=devstack_profile detail=\"signing keys named test-* are accepted (internal JWT: {}, policy "
                        + "bundles: {}); this server must not run in production\"", properties.getInternalJwt().isAllowTestKeys(),
                        properties.getPolicy().isAllowTestKeys());
            }
        }
        boolean testKeysAllowed = dev || devstack;

        if (dev && !loopback(environment.getProperty("server.address"))) {
            problems.add("with the 'dev' profile server.address must be a loopback address (127.0.0.1, ::1, localhost), "
                    + "not '" + environment.getProperty("server.address") + "': the dev relaxations (test keys, no "
                    + "mTLS, in-memory jti) must not be reachable from the network");
        }

        if (dev) {
            LOG.warn("event=dev_profile_overrides overridden=[{}] detail=\"server.ssl disabled, mtls.required=false, "
                    + "replay-store=memory, jwks-ssl-bundle ignored (profile dev, loopback only)\"",
                    environment.getProperty(PlatformDefaultsEnvironmentPostProcessor.OVERRIDDEN_KEY, ""));
            DevProfileEnvironment.deploymentSignal(environment)
                    .ifPresent(signal -> problems.add(DevProfileEnvironment.refusal(signal)));
            if (separateManagementServer() && !loopback(environment.getProperty("management.server.address"))) {
                problems.add("with the 'dev' profile and a separate management.server.port the management server "
                        + "must listen on a loopback address (management.server.address=127.0.0.1), not '"
                        + environment.getProperty("management.server.address") + "': it is outside the internal JWT "
                        + "and mTLS checks and would expose the actuator endpoints to the network");
            }
        }

        String system = properties.system();
        if (system != null && COMPONENT_ID.matcher(serverId).matches() && !SYSTEM_IDS.contains(system)) {
            String message = "aiwcorpo.mcp.platform.server-id '" + serverId + "': system '" + system + "' is not a "
                    + "SystemId of the contracts " + SYSTEM_IDS + "; audit events (server) and credential_required "
                    + "(system) would not match the contract and credential_required would reach clients as internal_error";
            if (dev) {
                LOG.warn("event=platform_config_warning detail=\"{}\"", message);
            } else {
                problems.add(message);
            }
        }

        if (isBlank(jwt.getJwksUri())) {
            problems.add("aiwcorpo.mcp.platform.internal-jwt.jwks-uri must point at the gateway's JWKS");
        } else if (!dev && !absoluteUrl(jwt.getJwksUri(), Set.of("https"))) {
            problems.add("aiwcorpo.mcp.platform.internal-jwt.jwks-uri must be an absolute https URL outside the 'dev' profile");
        }
        for (String path : jwt.getUnauthenticatedPaths()) {
            if (!ALLOWED_UNAUTHENTICATED_PATHS.contains(path)) {
                problems.add("aiwcorpo.mcp.platform.internal-jwt.unauthenticated-paths may contain only "
                        + ALLOWED_UNAUTHENTICATED_PATHS + ", not '" + path + "'");
            }
        }
        String protocol = environment.getProperty("spring.ai.mcp.server.protocol", "STREAMABLE");
        if (!"STREAMABLE".equalsIgnoreCase(protocol.trim())) {
            problems.add("spring.ai.mcp.server.protocol must be STREAMABLE (the transport the gateway uses and the "
                    + "platform's checks are built for), not " + protocol);
        }
        if (!dev && separateManagementServer()) {
            problems.add("management.server.port must be unset or equal to server.port outside the 'dev' profile: "
                    + "the internal JWT and mTLS checks do not cover a separate management server");
        }
        if (isBlank(jwt.getIssuer())) {
            problems.add("aiwcorpo.mcp.platform.internal-jwt.issuer must not be empty");
        }
        if (jwt.getClockSkew().isNegative() || jwt.getClockSkew().compareTo(Duration.ofSeconds(30)) > 0) {
            problems.add("aiwcorpo.mcp.platform.internal-jwt.clock-skew must be between 0 and 30s (C2)");
        }
        if (jwt.getMissingTokenEventsPerSecond() < 1) {
            problems.add("aiwcorpo.mcp.platform.internal-jwt.missing-token-events-per-second must be at least 1: "
                    + "0 would write no audit event for a request without a token");
        }
        if (!jwt.getMaxLifetime().isPositive() || jwt.getMaxLifetime().compareTo(Duration.ofSeconds(60)) > 0) {
            problems.add("aiwcorpo.mcp.platform.internal-jwt.max-lifetime must be between 1s and 60s (C2)");
        }
        String connectUrl = properties.getCredential().getConnectUrl();
        if (isBlank(connectUrl) || !absoluteUrl(connectUrl.replace("{system}", "jira"), Set.of("http", "https"))) {
            problems.add("aiwcorpo.mcp.platform.credential.connect-url must be the portal URL for connecting an "
                    + "account, with {system} as placeholder (sent in credential_required)");
        }

        if (properties.getTools().getMaxFilteredElements() < 1) {
            problems.add("aiwcorpo.mcp.platform.tools.max-filtered-elements must be at least 1");
        }
        policyProblems(properties.getPolicy(), dev, testKeysAllowed, problems);
        manifestProblems(properties.getManifest(), problems);
        credentialTypeProblems(properties.getManifest(), problems);

        if (!dev) {
            if (properties.getMtls().isRequired()
                    && !"need".equalsIgnoreCase(environment.getProperty("server.ssl.client-auth", "").trim())) {
                problems.add("server.ssl.client-auth=need is required outside the 'dev' profile: the gateway's client "
                        + "certificate is verified by the server's TLS (truststore with the internal CA)");
            }
            if (properties.getAuthMode() == AuthMode.DEV_PAT) {
                problems.add("aiwcorpo.mcp.platform.auth-mode=dev-pat is allowed only with the 'dev' profile");
            }
            if (jwt.isAllowTestKeys() && !testKeysAllowed) {
                problems.add("aiwcorpo.mcp.platform.internal-jwt.allow-test-keys=true is allowed only with the 'dev' or the "
                        + "'devstack' profile");
            }
            if (jwt.getReplayStore() == McpPlatformProperties.ReplayStore.MEMORY) {
                problems.add("aiwcorpo.mcp.platform.internal-jwt.replay-store=memory is allowed only with the 'dev' profile");
            }
            if (!properties.getMtls().isRequired()) {
                problems.add("aiwcorpo.mcp.platform.mtls.required=false is allowed only with the 'dev' profile");
            }
        }

        Binder binder = Binder.get(environment);
        if (binder.bind("aiwcorpo.mcp.platform.guard", Bindable.mapOf(String.class, Object.class)).isBound()) {
            problems.add("aiwcorpo.mcp.platform.guard.* was removed in 3.0 (identity, privileges and confirmations "
                    + "moved to the gateway); remove it, see MIGRATION-3.0.md");
        }
        if (environment.containsProperty("aiwcorpo.mcp.platform.pat-header-prefix")) {
            problems.add("aiwcorpo.mcp.platform.pat-header-prefix was removed in 3.0; use auth-mode=dev-pat with "
                    + "dev-pat.header-prefix (dev profile only), see MIGRATION-3.0.md");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid MCP platform configuration:\n - " + String.join("\n - ", problems));
        }
    }

    private static void policyProblems(McpPlatformProperties.Policy policy, boolean dev, boolean testKeysAllowed,
                                       List<String> problems) {
        channelProblems("main", policy.getMain(), true, dev, problems);
        channelProblems("overrides", policy.getOverrides(), !isBlank(policy.getOverrides().getUrl()), dev, problems);
        if (!dev) {
            if (isBlank(policy.getStateDir())) {
                problems.add("aiwcorpo.mcp.platform.policy.state-dir is required outside the 'dev' profile: without it a "
                        + "restart accepts an older signed bundle again (rollback protection, CC-066)");
            }
        }
        if (policy.isAllowTestKeys() && !testKeysAllowed) {
            problems.add("aiwcorpo.mcp.platform.policy.allow-test-keys=true is allowed only with the 'dev' or the 'devstack' profile");
        }
        if (!policy.getStartupTimeout().isPositive() || !policy.getOverridesGrace().isPositive()) {
            problems.add("aiwcorpo.mcp.platform.policy.startup-timeout and overrides-grace must be positive");
        }
    }

    private static void channelProblems(String name, McpPlatformProperties.PolicyChannel channel, boolean required,
                                        boolean dev, List<String> problems) {
        String prefix = "aiwcorpo.mcp.platform.policy." + name;
        if (!required) {
            return;
        }
        if (isBlank(channel.getUrl())) {
            problems.add(prefix + ".url must point at the latest.json of the " + name + " policy bundle (C7)");
        } else if (!absoluteUrl(channel.getUrl(), dev ? Set.of("http", "https") : Set.of("https"))) {
            problems.add(prefix + ".url must be an absolute " + (dev ? "http(s)" : "https") + " URL"
                    + (dev ? "" : " outside the 'dev' profile"));
        }
        if (isBlank(channel.getKeyId())) {
            problems.add(prefix + ".key-id must name the signing key (test-* keys only with policy.allow-test-keys in 'dev')");
        }
        if (isBlank(channel.getPublicKey()) && isBlank(channel.getPublicKeyFile())) {
            problems.add(prefix + ".public-key or " + prefix + ".public-key-file must hold the Ed25519 public key (SPKI PEM)");
        }
        if (channel.getMinimumRevision() != null
                && !channel.getMinimumRevision().matches("^[0-9]{14}-[0-9a-f]{7}$")) {
            problems.add(prefix + ".minimum-revision must look like 20261028194138-ad20673");
        }
        if (channel.getRefreshInterval() != null && !channel.getRefreshInterval().isPositive()) {
            problems.add(prefix + ".refresh-interval must be positive");
        }
    }

    private static final Pattern SEMVER = Pattern.compile(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?$");
    private static final Set<String> CREDENTIAL_TYPES =
            Set.of("user-oauth", "user-secret", "token-exchange", "service-account");

    private static void manifestProblems(McpPlatformProperties.Manifest manifest, List<String> problems) {
        if (manifest.getVersion() == null || !SEMVER.matcher(manifest.getVersion()).matches()) {
            problems.add("aiwcorpo.mcp.platform.manifest.version must be the SemVer of this server (C6), for example 1.4.0");
        }
        if (isBlank(manifest.getInternalUrl()) || !absoluteUrl(manifest.getInternalUrl(), Set.of("https"))) {
            problems.add("aiwcorpo.mcp.platform.manifest.internal-url must be the https URL the gateway reaches this server at (C6)");
        }
        if (manifest.isCredentialRequired() && manifest.getAcceptedCredentialTypes().isEmpty()) {
            problems.add("aiwcorpo.mcp.platform.manifest.accepted-credential-types must list at least one of "
                    + CREDENTIAL_TYPES + " when credential-required is true (C6)");
        }
        for (String type : manifest.getAcceptedCredentialTypes()) {
            if (!CREDENTIAL_TYPES.contains(type)) {
                problems.add("aiwcorpo.mcp.platform.manifest.accepted-credential-types: '" + type + "' is not one of "
                        + CREDENTIAL_TYPES);
            }
        }
    }

    /**
     * The types the server accepts are one set (CC-166, CC-168): the first is the default type of {@code context.credentialType}, so the set is
     * never empty, and {@code service-account} is not mixed with the types of a user, because a mixed set would hide the service account from
     * the rules of {@code credential-type.cedar}.
     */
    private static void credentialTypeProblems(McpPlatformProperties.Manifest manifest, List<String> problems) {
        List<String> accepted = manifest.getAcceptedCredentialTypes();
        if (accepted.isEmpty() && !manifest.isCredentialRequired()) {
            problems.add("aiwcorpo.mcp.platform.manifest.accepted-credential-types must list at least one of " + CREDENTIAL_TYPES
                    + " also without credential-required: the first one is the credentialType of every policy request");
        }
        if (accepted.contains("service-account") && accepted.stream().anyMatch(type -> !"service-account".equals(type))) {
            problems.add("aiwcorpo.mcp.platform.manifest.accepted-credential-types mixes service-account with the credential types of a user "
                    + accepted + " (CC-168): use service-account alone or only user types");
        }
    }

    /**
     * Whether Spring Boot runs the actuator on a server of its own: a management port other than the main one,
     * compared as numbers. Port 0 is always a separate server (a random port each, even when {@code server.port}
     * is 0 as well) and -1 turns the actuator's HTTP endpoints off.
     */
    private boolean separateManagementServer() {
        String managementPort = environment.getProperty("management.server.port");
        if (managementPort == null || managementPort.isBlank()) {
            return false;
        }
        try {
            int management = Integer.parseInt(managementPort.trim());
            if (management < 0) {
                return false;
            }
            return management == 0 || management != Integer.parseInt(environment.getProperty("server.port", "8080").trim());
        } catch (NumberFormatException ex) {
            return true;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean absoluteUrl(String value, Set<String> schemes) {
        try {
            URI uri = new URI(value);
            return uri.isAbsolute() && uri.getHost() != null && schemes.contains(uri.getScheme());
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * True for a literal loopback address or {@code localhost}; a name is never resolved (DNS must not decide what
     * "local" means), and a blank value is not loopback because it means the wildcard.
     */
    public static boolean loopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String host = address.trim();
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        // Only literal addresses: resolving a name here would let DNS decide what "local" means.
        if (!host.matches("[0-9.]+") && !host.contains(":")) {
            return false;
        }
        try {
            return java.net.InetAddress.getByName(host).isLoopbackAddress();
        } catch (java.net.UnknownHostException ex) {
            return false;
        }
    }

    private static Set<String> systemIds() {
        String resource = "/mcp-contracts/schemas/common.schema.json";
        try (InputStream in = PlatformConfigurationGuard.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing: mcp-contracts must be on the classpath");
            }
            Set<String> ids = new LinkedHashSet<>();
            JsonMapper.builder().build().readTree(in).required("$defs").required("SystemId").required("enum")
                    .forEach(id -> ids.add(id.asString()));
            return Set.copyOf(ids);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read " + resource, ex);
        }
    }
}
