package pl.aiwcorpo.mcp.platform.observability;

import java.util.Arrays;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.input.ToolInputPolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;

/**
 * Wraps every {@link ToolCallbackProvider} bean (and every stand-alone {@link ToolCallback} bean) in
 * {@link AuditingToolCallback}. A post-processor rather than a base class: servers keep declaring an ordinary
 * {@code ToolCallbackProvider} and nothing in a server has to remember to opt in.
 *
 * <p>Neither this class nor {@link AuditingToolCallback} can be constructed outside the package, and the wrapper is
 * final: an {@code AuditingToolCallback} met here was made by this post-processor, with the policy of the active
 * profiles and the server's interceptors, so it is not wrapped again.
 *
 * <p>Dependencies are resolved lazily through {@link ObjectProvider} so registering this post-processor does
 * not force early instantiation of the beans it needs.
 */
public final class ToolCallbackAuditPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<AuditLogger> auditLogger;
    private final ObjectProvider<McpErrorMapper> errorMapper;
    private final ObjectProvider<ToolRiskRegistry> registry;
    private final ObjectProvider<ToolCallInterceptor> interceptors;
    private final ToolInputPolicy inputPolicy;
    private final long maxResultBytes;
    private final ObjectProvider<ResourcePolicy> resourcePolicy;

    ToolCallbackAuditPostProcessor(ObjectProvider<AuditLogger> auditLogger,
                                   ObjectProvider<McpErrorMapper> errorMapper,
                                   ObjectProvider<ToolRiskRegistry> registry,
                                   ObjectProvider<ToolCallInterceptor> interceptors) {
        this(auditLogger, errorMapper, registry, interceptors, null, McpPlatformProperties.DEFAULT_MAX_RESULT_SIZE.toBytes(),
                null);
    }

    /**
     * {@code inputPolicy} is a value, not a bean lookup: the policy comes from the active profiles when the
     * post-processor is created, so no bean of a server can change what the wrapper enforces. {@code null} means
     * {@link ToolInputPolicy#STRICT}.
     */
    ToolCallbackAuditPostProcessor(ObjectProvider<AuditLogger> auditLogger,
                                   ObjectProvider<McpErrorMapper> errorMapper,
                                   ObjectProvider<ToolRiskRegistry> registry,
                                   ObjectProvider<ToolCallInterceptor> interceptors,
                                   ToolInputPolicy inputPolicy, long maxResultBytes,
                                   ObjectProvider<ResourcePolicy> resourcePolicy) {
        this.maxResultBytes = maxResultBytes;
        this.resourcePolicy = resourcePolicy;
        this.inputPolicy = inputPolicy == null ? ToolInputPolicy.STRICT : inputPolicy;
        this.auditLogger = auditLogger;
        this.errorMapper = errorMapper;
        this.registry = registry;
        this.interceptors = interceptors;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ToolCallbackProvider provider) {
            return bean instanceof AuditedToolCallbackProvider ? bean : new AuditedToolCallbackProvider(provider, this);
        }
        if (bean instanceof ToolCallback callback && !(bean instanceof AuditingToolCallback)) {
            return decorate(callback);
        }
        return bean;
    }

    ToolCallback decorate(ToolCallback callback) {
        if (callback instanceof AuditingToolCallback) {
            return callback;
        }
        List<ToolCallInterceptor> chain = interceptors.orderedStream().toList();
        return new AuditingToolCallback(callback, auditLogger.getObject(), errorMapper.getObject(),
                registry.getObject(), chain, inputPolicy, maxResultBytes,
                resourcePolicy == null ? null : resourcePolicy.getIfAvailable());
    }

    /** Marker + decorator; the marker keeps a second post-processing pass from double-wrapping. */
    static final class AuditedToolCallbackProvider implements ToolCallbackProvider {

        private final ToolCallbackProvider delegate;
        private final ToolCallbackAuditPostProcessor processor;
        private volatile ToolCallback[] decorated;

        AuditedToolCallbackProvider(ToolCallbackProvider delegate, ToolCallbackAuditPostProcessor processor) {
            this.delegate = delegate;
            this.processor = processor;
        }

        /**
         * Decorated once: decorating registers each tool, and a provider asked twice must not look like two
         * tools with the same name. Decorating also validates the risk flag, so a tool without one stops the
         * server here, while it is starting.
         */
        @Override
        public ToolCallback[] getToolCallbacks() {
            ToolCallback[] result = decorated;
            if (result == null) {
                synchronized (this) {
                    if (decorated == null) {
                        decorated = Arrays.stream(delegate.getToolCallbacks()).map(processor::decorate)
                                .toArray(ToolCallback[]::new);
                    }
                    result = decorated;
                }
            }
            return result.clone();
        }
    }
}
