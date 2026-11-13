package pl.aiwcorpo.mcp.platform.server;

import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;

/** Registers the starter's MCP server customizer, the check against a competing {@code @Primary} one and the session sweep and cap. */
@AutoConfiguration
@ConditionalOnClass(McpSyncServerCustomizer.class)
@EnableConfigurationProperties(McpPlatformProperties.class)
public class PlatformMcpServerAutoConfiguration {

    /** Static so that registering a bean factory post-processor does not initialize this class early. */
    @Bean
    static ForeignPrimaryCustomizerCheck foreignPrimaryCustomizerCheck() {
        return new ForeignPrimaryCustomizerCheck();
    }

    @Bean(ForeignPrimaryCustomizerCheck.STARTER_BEAN)
    @Primary
    PlatformMcpServerCustomizer platformMcpServerCustomizer(ObjectProvider<McpSyncServerCustomizer> serverCustomizers) {
        return new PlatformMcpServerCustomizer(serverCustomizers);
    }

    @Bean
    IdleSessionSweeper idleSessionSweeper(ObjectProvider<WebMvcStreamableServerTransportProvider> transport,
                                          McpPlatformProperties props, Environment environment) {
        return new IdleSessionSweeper(transport::getIfAvailable, props.getSession().getIdleTimeout(),
                props.getSession().getMaxCount(),
                environment.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp"));
    }
}
