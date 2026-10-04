package pl.aiwcorpo.mcp.platform.observability;

import java.util.Arrays;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.guard.ToolGuard;

/**
 * Wraps every {@link ToolCallbackProvider} bean (and every stand-alone {@link ToolCallback} bean) so
 * the tools are guarded, audited and their failures sanitized. This is what turns {@link AuditLogger} and {@link McpErrorMapper} from injectable beans
 * into behaviour that actually runs on the tool-invocation path.
 *
 * <p>A post-processor rather than a required base class: servers keep declaring an ordinary
 * {@code ToolCallbackProvider} (an explicit, reviewed allow-list of tools) and get the baseline for
 * free, exactly like the servlet filters. Nothing in a server has to remember to opt in.
 *
 * <p>Dependencies are resolved lazily through {@link ObjectProvider} so registering this
 * post-processor does not force early instantiation of the beans it needs.
 */
public class ToolCallbackAuditPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<AuditLogger> auditLogger;
    private final ObjectProvider<McpErrorMapper> errorMapper;
    private final ObjectProvider<ToolGuard> guard;   // null or empty = guard disabled

    public ToolCallbackAuditPostProcessor(ObjectProvider<AuditLogger> auditLogger,
                                          ObjectProvider<McpErrorMapper> errorMapper) {
        this(auditLogger, errorMapper, null);
    }

    public ToolCallbackAuditPostProcessor(ObjectProvider<AuditLogger> auditLogger,
                                          ObjectProvider<McpErrorMapper> errorMapper,
                                          ObjectProvider<ToolGuard> guard) {
        this.auditLogger = auditLogger;
        this.errorMapper = errorMapper;
        this.guard = guard;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ToolCallbackProvider provider) {
            return bean instanceof AuditedToolCallbackProvider
                    ? bean
                    : new AuditedToolCallbackProvider(provider, auditLogger, errorMapper, guard);
        }
        // Spring AI also exposes tools declared as plain ToolCallback beans. Since 2.0.0 those get the
        // same treatment; before, a tool declared that way was neither audited nor sanitized.
        if (bean instanceof ToolCallback callback && !(bean instanceof AuditingToolCallback)) {
            return new AuditingToolCallback(callback, auditLogger.getObject(), errorMapper.getObject(),
                    guard == null ? null : guard.getIfAvailable());
        }
        return bean;
    }

    /** Marker + decorator; the marker keeps a second post-processing pass from double-wrapping. */
    static final class AuditedToolCallbackProvider implements ToolCallbackProvider {

        private final ToolCallbackProvider delegate;
        private final ObjectProvider<AuditLogger> auditLogger;
        private final ObjectProvider<McpErrorMapper> errorMapper;
        private final ObjectProvider<ToolGuard> guard;

        AuditedToolCallbackProvider(ToolCallbackProvider delegate,
                                    ObjectProvider<AuditLogger> auditLogger,
                                    ObjectProvider<McpErrorMapper> errorMapper,
                                    ObjectProvider<ToolGuard> guard) {
            this.delegate = delegate;
            this.auditLogger = auditLogger;
            this.errorMapper = errorMapper;
            this.guard = guard;
        }

        /**
         * Decorating a tool also validates it against the guard, so a tool without a risk flag
         * surfaces here — while the server is starting — as an exception that stops it.
         */
        @Override
        public ToolCallback[] getToolCallbacks() {
            AuditLogger audit = auditLogger.getObject();
            McpErrorMapper errors = errorMapper.getObject();
            ToolGuard toolGuard = guard == null ? null : guard.getIfAvailable();
            return Arrays.stream(delegate.getToolCallbacks())
                    .map(callback -> callback instanceof AuditingToolCallback
                            ? callback
                            : new AuditingToolCallback(callback, audit, errors, toolGuard))
                    .toArray(ToolCallback[]::new);
        }
    }
}
