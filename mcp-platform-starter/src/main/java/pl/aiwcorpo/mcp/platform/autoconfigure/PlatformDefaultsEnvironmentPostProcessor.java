package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Defaults the platform needs from Spring Boot, at the lowest precedence so a server can still override them.
 *
 * <p>{@code management.health.redis.enabled=false}: Boot's Redis health check runs {@code INFO}, which the
 * per-server Redis user may not run (ACL with an explicit command list, see mcp-platform-dev), so the
 * health endpoint would report DOWN while the jti store works.
 */
public class PlatformDefaultsEnvironmentPostProcessor implements EnvironmentPostProcessor {

    static final String SOURCE_NAME = "mcpPlatformDefaults";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getPropertySources().contains(SOURCE_NAME)) {
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME,
                    Map.of("management.health.redis.enabled", "false")));
        }
    }
}
