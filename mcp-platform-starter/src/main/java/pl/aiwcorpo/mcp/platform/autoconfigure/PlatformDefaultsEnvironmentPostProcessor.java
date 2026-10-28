package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Defaults the platform needs from Spring Boot, at the lowest precedence so a server can still override them.
 *
 * <ul>
 *   <li>{@code management.health.redis.enabled=false}: Boot's Redis health check runs {@code INFO}, which the
 *       per-server Redis user may not run (ACL with an explicit command list, see mcp-platform-dev), so the
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
 *   <li>With the {@code dev} profile active only: {@code server.address=127.0.0.1}. The dev relaxations (test
 *       keys, no mTLS) must not be reachable from the network; {@code PlatformConfigurationGuard} refuses any
 *       non-loopback address in dev, and for a separate management port it requires a loopback
 *       {@code management.server.address} (Spring Boot rejects that property without a separate port, so it cannot
 *       be defaulted here).</li>
 * </ul>
 *
 * <p>With {@code dev} as the only active profile the platform also forces, above the server's own
 * {@code application*.yml} (command line, system properties and environment variables still win), what a local run
 * on a developer's machine cannot have: {@code server.ssl.enabled=false} (no certificates), {@code mtls.required=false},
 * {@code internal-jwt.replay-store=memory} (no Redis) and an empty {@code internal-jwt.jwks-ssl-bundle} (no internal
 * CA). So the configuration of a real server starts in {@code dev} as it is, without per-run overrides. This is safe
 * for the same reason the rest of {@code dev} is: the guard refuses a non-loopback {@code server.address} in {@code dev}.
 * {@code allow-test-keys} is not forced; set it where the gateway signs with the published test keys.
 *
 * With {@code dev} as the only profile in a deployment (a cloud platform or a systemd service, see
 * {@link DevProfileEnvironment}) the server does not start at all.
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
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, defaults));
        }
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        if (active.equals(List.of(PlatformConfigurationGuard.DEV_PROFILE))
                && !environment.getPropertySources().contains(DEV_SOURCE_NAME)) {
            DevProfileEnvironment.deploymentSignal(environment).ifPresent(signal -> {
                throw new IllegalStateException(DevProfileEnvironment.refusal(signal));
            });
            Map<String, Object> overrides = new HashMap<>(Map.of(
                    "server.ssl.enabled", "false",
                    "aiwcorpo.mcp.platform.mtls.required", "false",
                    "aiwcorpo.mcp.platform.internal-jwt.replay-store", "memory",
                    "aiwcorpo.mcp.platform.internal-jwt.jwks-ssl-bundle", ""));
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
    }
}
