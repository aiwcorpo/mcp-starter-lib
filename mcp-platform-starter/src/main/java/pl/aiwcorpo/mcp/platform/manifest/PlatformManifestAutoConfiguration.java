package pl.aiwcorpo.mcp.platform.manifest;

import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.input.ToolInputPolicy;

/** The C6 manifest: built and checked at startup, served at {@code GET /mcp-manifest}. */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(McpPlatformProperties.class)
public class PlatformManifestAutoConfiguration {

    @Bean
    ManifestCheck manifestCheck(ObjectProvider<McpSyncServer> servers, McpPlatformProperties props, ToolRiskRegistry registry,
                          ToolInputPolicy inputPolicy) {
        return new ManifestCheck(servers, props, registry, inputPolicy.requireBoundedSchema());
    }

    @Bean
    ManifestEndpoint manifestEndpoint(ManifestCheck check) {
        return new ManifestEndpoint(check);
    }
}
