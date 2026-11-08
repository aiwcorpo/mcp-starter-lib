package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
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
 *   <li>The development relaxations ({@code auth-mode=dev-pat}, {@code internal-jwt.allow-test-keys}, plain-http JWKS, a separate
 *       management port) only when {@code dev} is the one and only active profile; {@code dev} next to another
 *       profile, through a profile group or as a default profile does not count and, when active with others,
 *       stops the server.</li>
 *   <li>Outside {@code dev}: TLS on the server port (an SSL bundle or key store; the starter derives one from {@code MCP_TLS_*} or generates a
 *       certificate) or {@code MCP_PLAIN_HTTP=true}, https JWKS, no separate management port. Never a client certificate on the port (CC-249).</li>
 *   <li>Unauthenticated paths only from {@link #ALLOWED_UNAUTHENTICATED_PATHS}; MCP transport STREAMABLE.</li>
 *   <li>Clock skew at most 30 s and token lifetime at most 60 s (C2).</li>
 *   <li>No removed settings ({@code guard.*}, {@code pat-header-prefix}, the 3.x {@code policy.*} of the signed bundles, and from 4.0.0 {@code mtls.*},
 *       {@code policy-data.*}, {@code internal-jwt.jwks-ssl-bundle}): left in place they would be ignored while the operator believes they still
 *       protect the server.</li>
 * </ul>
 */
public class PlatformConfigurationGuard implements InitializingBean {

    /** The profile under which development relaxations are allowed. */
    public static final String DEV_PROFILE = "dev";

    /**
     * The profile of a shared development stack (containers of the dev stack): everything is as in production (TLS,
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

    /** {@code common.schema.json#/$defs/SystemId} from the contracts jar on the classpath: a pattern, the systems themselves are runtime data. */
    static final Pattern SYSTEM_ID = contractPattern("SystemId");

    /** {@code common.schema.json#/$defs/ServerId}: {@code mcp-<system>}. */
    private static final Pattern SERVER_ID = contractPattern("ServerId");

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
        if (serverId == null || !SERVER_ID.matcher(serverId).matches()) {
            problems.add("aiwcorpo.mcp.platform.server-id must be set to mcp-<system>, with a system of lower case letters and digits "
                    + "(" + SERVER_ID.pattern() + "): it is the aud of the internal JWT");
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
                LOG.warn("event=devstack_profile detail=\"signing keys named test-* are accepted (internal JWT: {}); this server "
                        + "must not run in production\"", properties.getInternalJwt().isAllowTestKeys());
            }
        }
        boolean testKeysAllowed = dev || devstack;

        if (dev && !loopback(environment.getProperty("server.address"))) {
            problems.add("with the 'dev' profile server.address must be a loopback address (127.0.0.1, ::1, localhost), "
                    + "not '" + environment.getProperty("server.address") + "': the dev relaxations (test keys, no "
                    + "TLS, in-memory jti) must not be reachable from the network");
        }

        if (dev) {
            LOG.warn("event=dev_profile_overrides overridden=[{}] detail=\"server.ssl disabled, replay-store=memory "
                    + "(profile dev, loopback only)\"",
                    environment.getProperty(PlatformDefaultsEnvironmentPostProcessor.OVERRIDDEN_KEY, ""));
            DevProfileEnvironment.deploymentSignal(environment)
                    .ifPresent(signal -> problems.add(DevProfileEnvironment.refusal(signal)));
            if (separateManagementServer() && !loopback(environment.getProperty("management.server.address"))) {
                problems.add("with the 'dev' profile and a separate management.server.port the management server "
                        + "must listen on a loopback address (management.server.address=127.0.0.1), not '"
                        + environment.getProperty("management.server.address") + "': it is outside the internal JWT "
                        + "check and would expose the actuator endpoints to the network");
            }
        }

        String system = properties.system();
        if (system != null && SERVER_ID.matcher(serverId).matches() && !SYSTEM_ID.matcher(system).matches()) {
            problems.add("aiwcorpo.mcp.platform.server-id '" + serverId + "': system '" + system + "' is not a SystemId of the contracts ("
                    + SYSTEM_ID.pattern() + ")");
        }

        if (isBlank(jwt.getJwksUri())) {
            problems.add("aiwcorpo.mcp.platform.internal-jwt.jwks-uri (MCP_GATEWAY_JWKS_URI) must point at the public JWKS of the gateway, "
                    + "https://<domain>/.well-known/mcp-jwks.json");
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
                    + "the internal JWT check does not cover a separate management server");
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
        manifestProblems(properties.getManifest(), problems);
        credentialTypeProblems(properties.getManifest(), problems);
        if (environment.containsProperty("aiwcorpo.mcp.platform.credential-type")) {
            problems.add("aiwcorpo.mcp.platform.credential-type was removed in 3.0.0-beta-7 (CC-166, CC-168): delete it and put the default type of "
                    + "the system first in aiwcorpo.mcp.platform.manifest.accepted-credential-types");
        }

        if (!dev) {
            tlsProblems(problems);
            if (properties.getAuthMode() == AuthMode.DEV_PAT) {
                problems.add("aiwcorpo.mcp.platform.auth-mode=dev-pat is allowed only with the 'dev' profile");
            }
            if (jwt.isAllowTestKeys() && !testKeysAllowed) {
                problems.add("aiwcorpo.mcp.platform.internal-jwt.allow-test-keys=true is allowed only with the 'dev' or the "
                        + "'devstack' profile");
            }
        }
        if (jwt.getReplayStore() == McpPlatformProperties.ReplayStore.REDIS && isBlank(environment.getProperty("spring.data.redis.host"))
                && isBlank(environment.getProperty("spring.data.redis.url"))) {
            problems.add("aiwcorpo.mcp.platform.internal-jwt.replay-store=redis needs MCP_REDIS_HOST (spring.data.redis.host); leave replay-store "
                    + "unset to use the memory of the process, which is right for one instance");
        }

        Binder binder = Binder.get(environment);
        if (binder.bind("aiwcorpo.mcp.platform.guard", Bindable.mapOf(String.class, Object.class)).isBound()) {
            problems.add("aiwcorpo.mcp.platform.guard.* was removed in 3.0 (identity, privileges and confirmations "
                    + "moved to the gateway); remove it");
        }
        if (binder.bind("aiwcorpo.mcp.platform.policy", Bindable.mapOf(String.class, Object.class)).isBound()) {
            problems.add("aiwcorpo.mcp.platform.policy.* (the signed policy bundles, their keys and state directory) was removed in 4.0.0: "
                    + "the policies are in the server's jar (mcp/policies/) and the scope mapping is pushed by the gateway, see the CHANGELOG, 4.0.0");
        }
        if (binder.bind("aiwcorpo.mcp.platform.policy-data", Bindable.mapOf(String.class, Object.class)).isBound()) {
            problems.add("aiwcorpo.mcp.platform.policy-data.* (url, tls-name, ssl-bundle, intervals) was removed in 4.0.0 (CC-249): the server no longer "
                    + "fetches policy data, the gateway pushes it to POST /.well-known/mcp-policy-data; remove the settings and MCP_GATEWAY_POLICY_DATA_URL, "
                    + "MCP_GATEWAY_TLS_NAME");
        }
        if (binder.bind("aiwcorpo.mcp.platform.mtls", Bindable.mapOf(String.class, Object.class)).isBound()) {
            problems.add("aiwcorpo.mcp.platform.mtls.* was removed in 4.0.0 (CC-249): the gateway presents no client certificate, it proves itself "
                    + "with the internal JWT; remove it");
        }
        if (environment.containsProperty("aiwcorpo.mcp.platform.internal-jwt.jwks-ssl-bundle")) {
            problems.add("aiwcorpo.mcp.platform.internal-jwt.jwks-ssl-bundle was removed in 4.0.0 (CC-249): the JWKS of the gateway is public https, "
                    + "trusted by the JVM's own trust store; remove it");
        }
        if (environment.containsProperty("aiwcorpo.mcp.platform.pat-header-prefix")) {
            problems.add("aiwcorpo.mcp.platform.pat-header-prefix was removed in 3.0; use auth-mode=dev-pat with "
                    + "dev-pat.header-prefix (dev profile only)");
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid MCP platform configuration:\n - " + String.join("\n - ", problems));
        }
    }

    /**
     * What the TLS of the server port must look like outside {@code dev}: either plain http by the explicit {@code MCP_PLAIN_HTTP=true} (which
     * the administrator also has to allow for the server at the gateway), or an SSL bundle or key store that is enabled. No client certificate.
     */
    private void tlsProblems(List<String> problems) {
        String clientAuth = environment.getProperty("server.ssl.client-auth", "").trim();
        if ("need".equalsIgnoreCase(clientAuth) || "want".equalsIgnoreCase(clientAuth)) {
            problems.add("server.ssl.client-auth=" + clientAuth + " was removed from the model in 4.0.0 (CC-249): the gateway presents no client "
                    + "certificate and a server that asks for one is unreachable; remove it");
        }
        boolean plain = PlatformTlsEnvironment.plainHttp(environment);
        boolean tls = !"false".equalsIgnoreCase(environment.getProperty("server.ssl.enabled", "").trim())
                && (!isBlank(environment.getProperty("server.ssl.bundle")) || !isBlank(environment.getProperty("server.ssl.key-store")));
        if (!plain && !tls) {
            problems.add("the server port has no TLS: give the server a certificate (MCP_TLS_KEYSTORE with MCP_TLS_STORE_PASSWORD, or MCP_TLS_CERT and "
                    + "MCP_TLS_KEY), leave them unset so that the starter generates one into MCP_STATE_DIR, or set MCP_PLAIN_HTTP=true for a server "
                    + "inside the Docker network of the platform");
        }
        String source = environment.getProperty(PlatformTlsEnvironment.SOURCE);
        if (plain) {
            LOG.warn("event=plain_http detail=\"the server port speaks http (MCP_PLAIN_HTTP=true): only for a server inside the Docker network of "
                    + "the platform, with allowPlainHttp set for it at the gateway\"");
        } else if (source != null) {
            LOG.info("event=tls_certificate source={} fingerprint={} detail=\"{}; the portal shows this fingerprint when the connection is approved: "
                    + "compare them\"", source, environment.getProperty(PlatformTlsEnvironment.FINGERPRINT, "unknown"),
                    environment.getProperty(PlatformTlsEnvironment.DETAIL, ""));
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
        if (!isBlank(manifest.getInternalUrl()) && !absoluteUrl(manifest.getInternalUrl(), Set.of("http", "https"))) {
            problems.add("aiwcorpo.mcp.platform.manifest.internal-url is informational and optional (C6); when set it must be an absolute http(s) URL");
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

    private static Pattern contractPattern(String definition) {
        String resource = "/mcp-contracts/schemas/common.schema.json";
        try (InputStream in = PlatformConfigurationGuard.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource + " is missing: mcp-contracts must be on the classpath");
            }
            return Pattern.compile(JsonMapper.builder().build().readTree(in).required("$defs").required(definition).required("pattern")
                    .asString());
        } catch (IOException ex) {
            throw new IllegalStateException("cannot read " + resource, ex);
        }
    }
}
