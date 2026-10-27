package pl.aiwcorpo.mcp.platform.observability;

import io.modelcontextprotocol.server.McpSyncServer;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.guard.RegisteredToolsCheck;
import pl.aiwcorpo.mcp.platform.guard.ToolHintsPostProcessor;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;

/**
 * Audit (C8), request id, tool registry and the decorator applied to every tool. All beans are
 * {@code @ConditionalOnMissingBean} so a server may override them.
 */
@AutoConfiguration
@EnableConfigurationProperties(McpPlatformProperties.class)
public class PlatformObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AuditLogger auditLogger(McpPlatformProperties props) {
        return new AuditLogger(props.getAuditLoggerName(), props.getServerId());
    }

    @Bean
    @ConditionalOnMissingBean
    CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    ToolRiskRegistry toolRiskRegistry(McpPlatformProperties props) {
        return new ToolRiskRegistry(props.getTools().getUnclassified());
    }

    /** Static so that registering a post-processor does not initialize this configuration early. */
    @Bean
    @ConditionalOnClass(ToolCallbackProvider.class)
    @ConditionalOnMissingBean
    static ToolCallbackAuditPostProcessor toolCallbackAuditPostProcessor(
            ObjectProvider<AuditLogger> auditLogger, ObjectProvider<McpErrorMapper> errorMapper,
            ObjectProvider<ToolRiskRegistry> registry, ObjectProvider<ToolCallInterceptor> interceptors) {
        return new ToolCallbackAuditPostProcessor(auditLogger, errorMapper, registry, interceptors);
    }

    @Bean
    @ConditionalOnMissingBean
    RegisteredToolsCheck registeredToolsCheck(ObjectProvider<McpSyncServer> servers, ToolRiskRegistry registry) {
        return new RegisteredToolsCheck(servers, registry);
    }

    @Bean
    @ConditionalOnClass(ToolCallbackProvider.class)
    @ConditionalOnMissingBean
    static ToolHintsPostProcessor toolHintsPostProcessor(ObjectProvider<ToolRiskRegistry> registry) {
        return new ToolHintsPostProcessor(registry);
    }
}
