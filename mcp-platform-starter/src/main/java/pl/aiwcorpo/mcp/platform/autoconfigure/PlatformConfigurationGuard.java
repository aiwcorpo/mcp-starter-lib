package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    public PlatformConfigurationGuard(McpPlatformProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
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

        if (!dev) {
            if (properties.getMtls().isRequired()
                    && !"need".equalsIgnoreCase(environment.getProperty("server.ssl.client-auth", "").trim())) {
                problems.add("server.ssl.client-auth=need is required outside the 'dev' profile: the gateway's client "
                        + "certificate is verified by the server's TLS (truststore with the internal CA)");
            }
            if (properties.getAuthMode() == AuthMode.DEV_PAT) {
                problems.add("aiwcorpo.mcp.platform.auth-mode=dev-pat is allowed only with the 'dev' profile");
            }
            if (jwt.isAllowTestKeys()) {
                problems.add("aiwcorpo.mcp.platform.internal-jwt.allow-test-keys=true is allowed only with the 'dev' profile");
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
