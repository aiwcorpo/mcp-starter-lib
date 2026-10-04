package pl.aiwcorpo.mcp.platform.error;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the shared {@link McpErrorMapper}, wiring in any {@link ToolErrorMapping} beans the
 * server contributed (ordered by the usual Spring {@code @Order} semantics).
 * {@code @ConditionalOnMissingBean} so a server may override with domain-specific mapping.
 */
@AutoConfiguration
public class PlatformErrorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    McpErrorMapper mcpErrorMapper(ObjectProvider<ToolErrorMapping> mappings) {
        return new McpErrorMapper(mappings.orderedStream().toList());
    }
}
