package pl.aiwcorpo.mcp.platform.server;

import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Registers the starter's MCP server customizer and the check against a competing {@code @Primary} one. */
@AutoConfiguration
@ConditionalOnClass(McpSyncServerCustomizer.class)
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
}
