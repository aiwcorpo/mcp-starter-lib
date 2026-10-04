package pl.aiwcorpo.mcp.platform.observability;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.guard.ToolGuard;

/**
 * Registers shared observability beans: the structured {@link AuditLogger}, the
 * {@link CorrelationIdFilter}, and the {@link ToolCallbackAuditPostProcessor} that applies auditing
 * and error sanitization to every tool the server exposes. All are {@code @ConditionalOnMissingBean}
 * so a server may override.
 */
@AutoConfiguration
@EnableConfigurationProperties(McpPlatformProperties.class)
public class PlatformObservabilityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AuditLogger auditLogger(McpPlatformProperties props) {
        return new AuditLogger(props.getAuditLoggerName());
    }

    @Bean
    @ConditionalOnMissingBean
    CorrelationIdFilter correlationIdFilter() {
        return new CorrelationIdFilter();
    }

    /**
     * Static so that registering this {@code BeanPostProcessor} does not drag the enclosing
     * configuration (and its dependencies) into early initialization.
     */
    @Bean
    @ConditionalOnClass(ToolCallbackProvider.class)
    @ConditionalOnMissingBean
    static ToolCallbackAuditPostProcessor toolCallbackAuditPostProcessor(
            ObjectProvider<AuditLogger> auditLogger, ObjectProvider<McpErrorMapper> errorMapper,
            ObjectProvider<ToolGuard> toolGuard) {
        return new ToolCallbackAuditPostProcessor(auditLogger, errorMapper, toolGuard);
    }
}
