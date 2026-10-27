package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.util.Arrays;
import java.util.HashMap;
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
 *       non-loopback address in dev.</li>
 * </ul>
 * Runs after the config data (application*.yml, profiles) is loaded, so the active profiles are known.
 */
public class PlatformDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "mcpPlatformDefaults";

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
    }
}
