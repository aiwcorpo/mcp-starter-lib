package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Stops the server at startup when its configuration would weaken the gateway model.
 *
 * <ul>
 *   <li>{@code server-id}, {@code internal-jwt.jwks-uri} and {@code credential.connect-url} are set.</li>
 *   <li>The development relaxations ({@code auth-mode=dev-pat}, {@code internal-jwt.allow-test-keys},
 *       {@code internal-jwt.replay-store=memory}, {@code mtls.required=false}) only with the {@code dev}
 *       profile active.</li>
 *   <li>Clock skew at most 30 s and token lifetime at most 60 s (C2).</li>
 *   <li>No 2.x settings ({@code guard.*}, {@code pat-header-prefix}): left in place they would be ignored
 *       while the operator believes they still protect the server.</li>
 * </ul>
 */
public class PlatformConfigurationGuard implements InitializingBean {

    /** The profile under which development relaxations are allowed. */
    public static final String DEV_PROFILE = "dev";

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
        if (isBlank(jwt.getJwksUri())) {
            problems.add("aiwcorpo.mcp.platform.internal-jwt.jwks-uri must point at the gateway's JWKS");
        }
        if (isBlank(jwt.getIssuer())) {
            problems.add("aiwcorpo.mcp.platform.internal-jwt.issuer must not be empty");
        }
        if (jwt.getClockSkew().isNegative() || jwt.getClockSkew().compareTo(Duration.ofSeconds(30)) > 0) {
            problems.add("aiwcorpo.mcp.platform.internal-jwt.clock-skew must be between 0 and 30s (C2)");
        }
        if (!jwt.getMaxLifetime().isPositive() || jwt.getMaxLifetime().compareTo(Duration.ofSeconds(60)) > 0) {
            problems.add("aiwcorpo.mcp.platform.internal-jwt.max-lifetime must be between 1s and 60s (C2)");
        }
        String connectUrl = properties.getCredential().getConnectUrl();
        if (isBlank(connectUrl) || !absoluteHttpUrl(connectUrl.replace("{system}", "jira"))) {
            problems.add("aiwcorpo.mcp.platform.credential.connect-url must be the portal URL for connecting an "
                    + "account, with {system} as placeholder (sent in credential_required)");
        }

        if (!environment.matchesProfiles(DEV_PROFILE)) {
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean absoluteHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            return uri.isAbsolute() && uri.getHost() != null
                    && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()));
        } catch (Exception ex) {
            return false;
        }
    }
}
