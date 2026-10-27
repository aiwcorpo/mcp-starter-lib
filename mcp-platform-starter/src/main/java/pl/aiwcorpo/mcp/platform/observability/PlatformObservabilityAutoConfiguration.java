package pl.aiwcorpo.mcp.platform.observability;

import io.modelcontextprotocol.server.McpSyncServer;
import java.util.List;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.guard.RegisteredToolsCheck;
import pl.aiwcorpo.mcp.platform.guard.ToolHintsPostProcessor;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.input.ToolInputPolicy;

/**
 * Audit (C8), request id, tool registry and the decorator applied to every tool. A server may override the audit
 * logger, the registry and the error mapper; the tool decorator and the input policy are not replaceable
 * ({@link ForeignToolPolicyCheck}).
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
    static ForeignToolPolicyCheck foreignToolPolicyCheck() {
        return new ForeignToolPolicyCheck();
    }

    /** Static so that registering a post-processor does not initialize this configuration early. */
    @Bean
    @ConditionalOnClass(ToolCallbackProvider.class)
    static ToolCallbackAuditPostProcessor toolCallbackAuditPostProcessor(
            ObjectProvider<AuditLogger> auditLogger, ObjectProvider<McpErrorMapper> errorMapper,
            ObjectProvider<ToolRiskRegistry> registry, ObjectProvider<ToolCallInterceptor> interceptors,
            ObjectProvider<ToolInputPolicy> inputPolicy) {
        return new ToolCallbackAuditPostProcessor(auditLogger, errorMapper, registry, interceptors, inputPolicy);
    }

    /** Only the one-and-only {@code dev} profile may run a tool without a closed inputSchema. */
    @Bean
    ToolInputPolicy toolInputPolicy(Environment environment) {
        boolean dev = List.of(environment.getActiveProfiles()).equals(List.of(PlatformConfigurationGuard.DEV_PROFILE));
        return dev ? ToolInputPolicy.DEV : ToolInputPolicy.STRICT;
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
