package pl.aiwcorpo.mcp.platform.observability;

import io.modelcontextprotocol.server.McpSyncServer;
import java.util.List;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.util.unit.DataSize;
import org.springframework.core.env.Environment;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.guard.RegisteredToolsCheck;
import pl.aiwcorpo.mcp.platform.guard.ToolHintsPostProcessor;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.input.ToolInputPolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;

/**
 * Audit (C8), request id, tool registry and the decorator applied to every tool. A server may override the audit
 * logger, the registry and the error mapper; the tool decorator, the input policy, the check that every tool went
 * through the decorator and the hints post-processor are not replaceable ({@link ForeignToolPolicyCheck}).
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
            ObjectProvider<ResourcePolicy> resourcePolicy, Environment environment) {
        DataSize maxResult = Binder.get(environment).bind("aiwcorpo.mcp.platform.tools.max-result-size", DataSize.class)
                .orElse(McpPlatformProperties.DEFAULT_MAX_RESULT_SIZE);
        if (maxResult.toBytes() <= 0) {
            throw new IllegalStateException("aiwcorpo.mcp.platform.tools.max-result-size must be positive");
        }
        return new ToolCallbackAuditPostProcessor(auditLogger, errorMapper, registry, interceptors,
                policyFor(environment), maxResult.toBytes(), resourcePolicy);
    }

    /** Only the one-and-only {@code dev} profile may run a tool without a closed inputSchema. */
    public static ToolInputPolicy policyFor(Environment environment) {
        boolean dev = List.of(environment.getActiveProfiles()).equals(List.of(PlatformConfigurationGuard.DEV_PROFILE));
        return dev ? ToolInputPolicy.DEV : ToolInputPolicy.STRICT;
    }

    /** For inspection; the wrapper does not read it, see {@link ToolCallbackAuditPostProcessor}. */
    @Bean
    ToolInputPolicy toolInputPolicy(Environment environment) {
        return policyFor(environment);
    }

    @Bean
    RegisteredToolsCheck registeredToolsCheck(ObjectProvider<McpSyncServer> servers, ToolRiskRegistry registry) {
        return new RegisteredToolsCheck(servers, registry);
    }

    @Bean
    @ConditionalOnClass(ToolCallbackProvider.class)
    static ToolHintsPostProcessor toolHintsPostProcessor(ObjectProvider<ToolRiskRegistry> registry) {
        return new ToolHintsPostProcessor(registry);
    }
}
