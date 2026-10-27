package pl.aiwcorpo.mcp.platform.error;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;

/**
 * Registers the shared {@link McpErrorMapper} with the server's {@link ToolErrorMapping} beans (in
 * {@code @Order}) and the portal link for {@code credential_required}.
 */
@AutoConfiguration
@EnableConfigurationProperties(McpPlatformProperties.class)
public class PlatformErrorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    McpErrorMapper mcpErrorMapper(ObjectProvider<ToolErrorMapping> mappings, McpPlatformProperties props) {
        return new McpErrorMapper(mappings.orderedStream().toList(), props.getCredential().getConnectUrl());
    }
}
