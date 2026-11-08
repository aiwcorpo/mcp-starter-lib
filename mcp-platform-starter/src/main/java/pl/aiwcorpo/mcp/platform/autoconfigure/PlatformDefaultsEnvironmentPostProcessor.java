package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Defaults the platform needs from Spring Boot, at the lowest precedence so a server can still override them.
 *
 * <ul>
 *   <li>{@code management.health.redis.enabled=false}: Boot's Redis health check runs {@code INFO}, which the
 *       per-server Redis user may not run (ACL with an explicit command list, see the dev stack), so the
 *       health endpoint would report DOWN while the jti store works.</li>
 *   <li>{@code spring.ai.mcp.server.protocol=STREAMABLE}, {@code type=SYNC},
 *       {@code streamable-http.mcp-endpoint=/mcp}: the transport the gateway speaks to (streamable HTTP) and the
 *       one the platform's ThreadLocals work with (SYNC). Spring AI's own default protocol does not mount
 *       {@code /mcp}, which left servers answering 404 on every call.</li>
 *   <li>{@code spring.ai.mcp.server.annotation-scanner.enabled=false}: tools declared with {@code @McpTool}
 *       would bypass the platform's decorator; {@code RegisteredToolsCheck} stops the server if one gets in
 *       anyway.</li>
 *   <li>{@code spring.data.redis.timeout=2s}: a hung Redis answers 503 quickly instead of holding request
 *       threads for the client's default minute.</li>
 *   <li>The environment variables of a deployment that map onto a platform property, when they are set and not blank:
 *       {@code MCP_STATE_DIR} ({@code aiwcorpo.mcp.platform.state-dir}), {@code MCP_GATEWAY_JWKS_URI}, {@code MCP_PAGING_CURSOR_KEY} and
 *       {@code MCP_REDIS_HOST}, {@code _PORT}, {@code _USER}, {@code _PASSWORD} ({@code spring.data.redis.*}: the jti store is Redis only when
 *       the host is set). A value in the server's own {@code application.yml} wins.</li>
 *   <li>With the {@code dev} profile active only: {@code server.address=127.0.0.1}. The dev relaxations (test
 *       keys, no TLS) must not be reachable from the network; {@code PlatformConfigurationGuard} refuses any
 *       non-loopback address in dev, and for a separate management port it requires a loopback
 *       {@code management.server.address} (Spring Boot rejects that property without a separate port, so it cannot
 *       be defaulted here).</li>
 * </ul>
 *
 * <p>With {@code dev} as the only active profile the platform also forces, above the server's own
 * {@code application*.yml} (command line, system properties and environment variables still win), what a local run
 * on a developer's machine cannot have: {@code server.ssl.enabled=false} (no certificates) and
 * {@code internal-jwt.replay-store=memory} (no Redis). So the configuration of a real server starts in {@code dev} as it is, without per-run overrides. This is safe
 * for the same reason the rest of {@code dev} is: the guard refuses a non-loopback {@code server.address} in {@code dev}.
 * {@code allow-test-keys} is not forced; set it where the gateway signs with the published test keys.
 *
 * With {@code dev} as the only profile in a deployment (a cloud platform or a systemd service, see
 * {@link DevProfileEnvironment}) the server does not start at all.
 *
 * Outside {@code dev} it also derives the TLS properties of the server port from {@code MCP_TLS_*}, {@code MCP_PLAIN_HTTP} and the state
 * directory ({@link PlatformTlsEnvironment}).
 *
 * Runs after the config data (application*.yml, profiles) is loaded, so the active profiles are known.
 */
public class PlatformDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "mcpPlatformDefaults";
    static final String DEV_SOURCE_NAME = "mcpPlatformDevOverrides";

    /** Keys of the dev overrides that replaced a different value of the server's own configuration. */
    static final String OVERRIDDEN_KEY = "mcp.platform.internal.dev-overridden";

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getPropertySources().contains(SOURCE_NAME)) {
            Map<String, Object> defaults = new HashMap<>(Map.of("management.health.redis.enabled", "false",
                            "spring.ai.mcp.server.protocol", "STREAMABLE",
                            "spring.ai.mcp.server.type", "SYNC",
                            "spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp",
                            "spring.ai.mcp.server.annotation-scanner.enabled", "false",
                            "spring.data.redis.timeout", "2s"));
            if (Arrays.asList(environment.getActiveProfiles()).contains(PlatformConfigurationGuard.DEV_PROFILE)) {
                defaults.put("server.address", "127.0.0.1");
            }
            fromVariable(environment, defaults, "MCP_STATE_DIR", "aiwcorpo.mcp.platform.state-dir");
            fromVariable(environment, defaults, "MCP_GATEWAY_JWKS_URI", "aiwcorpo.mcp.platform.internal-jwt.jwks-uri");
            fromVariable(environment, defaults, "MCP_PAGING_CURSOR_KEY", "aiwcorpo.mcp.platform.paging.cursor-key");
            fromVariable(environment, defaults, "MCP_REDIS_HOST", "spring.data.redis.host");
            fromVariable(environment, defaults, "MCP_REDIS_PORT", "spring.data.redis.port");
            fromVariable(environment, defaults, "MCP_REDIS_USER", "spring.data.redis.username");
            fromVariable(environment, defaults, "MCP_REDIS_PASSWORD", "spring.data.redis.password");
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
        }
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        refuseExcludedPlatform(environment);
        if (active.equals(List.of(PlatformConfigurationGuard.DEV_PROFILE))
                && !environment.getPropertySources().contains(DEV_SOURCE_NAME)) {
            DevProfileEnvironment.deploymentSignal(environment).ifPresent(signal -> {
                throw new IllegalStateException(DevProfileEnvironment.refusal(signal));
            });
            Map<String, Object> overrides = new HashMap<>(Map.of(
                    "server.ssl.enabled", "false",
                    "aiwcorpo.mcp.platform.internal-jwt.replay-store", "memory"));
            // A value the server's own configuration sets differently is what a deployment looks like.
            List<String> changed = overrides.entrySet().stream()
                    .filter(entry -> environment.getProperty(entry.getKey()) != null
                            && !entry.getValue().equals(environment.getProperty(entry.getKey())))
                    .map(Map.Entry::getKey).sorted().toList();
            // Logging is not set up yet at this point; PlatformConfigurationGuard writes the line once it is.
            overrides.put(OVERRIDDEN_KEY, String.join(",", changed));
            MapPropertySource source = new MapPropertySource(DEV_SOURCE_NAME, overrides);
            String firstConfigFile = environment.getPropertySources().stream().map(p -> p.getName())
                    .filter(name -> name.startsWith("Config resource") || name.startsWith("applicationConfig"))
                    .findFirst().orElse(null);
            if (firstConfigFile == null) {
                environment.getPropertySources().addLast(source);
            } else {
                environment.getPropertySources().addBefore(firstConfigFile, source);
            }
        }
        PlatformTlsEnvironment.apply(environment);
    }

    private static void fromVariable(ConfigurableEnvironment environment, Map<String, Object> defaults, String variable, String property) {
        String value = environment.getProperty(variable);
        if (value != null && !value.isBlank()) {
            defaults.put(property, value.trim());
        }
    }

    /**
     * {@code spring.autoconfigure.exclude} (also as {@code SPRING_AUTOCONFIGURE_EXCLUDE}) can switch off the
     * platform's auto-configurations, and with them the configuration guard and the internal JWT check, in a
     * profile where nothing else would notice, {@code dev} included. A Spring test context (slice tests
     * leave the platform out on purpose) is not checked. A {@code @SpringBootApplication(exclude = ...)} is not
     * a property and is caught by {@link PlatformPresenceCheck} instead.
     */
    private static void refuseExcludedPlatform(ConfigurableEnvironment environment) {
        if (DevProfileEnvironment.inSpringTestContext()) {
            return;
        }
        String[] excluded = Binder.get(environment).bind("spring.autoconfigure.exclude", String[].class).orElse(new String[0]);
        List<String> platform = Arrays.stream(excluded).map(String::trim)
                .filter(name -> name.startsWith("pl.aiwcorpo.mcp.platform.")).toList();
        if (!platform.isEmpty()) {
            throw new IllegalStateException("spring.autoconfigure.exclude lists " + platform + ": the platform's "
                    + "auto-configurations (configuration guard, internal JWT check, audit wrapper) cannot be switched "
                    + "off, in the 'dev' profile either (without the guard the profile's relaxations are not tied to a "
                    + "loopback address); remove them from the exclusion");
        }
    }
}
