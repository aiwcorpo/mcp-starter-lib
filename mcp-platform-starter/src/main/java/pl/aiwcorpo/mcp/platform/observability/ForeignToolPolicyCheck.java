package pl.aiwcorpo.mcp.platform.observability;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;
import pl.aiwcorpo.mcp.platform.input.ToolInputPolicy;

/**
 * Outside the {@code dev} profile, refuses to start when a server declares its own {@link ToolInputPolicy} or
 * {@link ToolCallbackAuditPostProcessor} bean.
 *
 * <p>Both used to be {@code @ConditionalOnMissingBean}: a server bean such as {@code ToolInputPolicy.DEV}
 * switched off the bounded-schema rule, and a replacement post-processor dropped the audit wrapper and the
 * argument validation, without any message. They are not extension points; what a server may add are
 * {@link ToolCallInterceptor}s. Works on bean definitions, so nothing is instantiated.
 */
public class ForeignToolPolicyCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    static final String INPUT_POLICY_BEAN = "toolInputPolicy";
    static final String AUDIT_POST_PROCESSOR_BEAN = "toolCallbackAuditPostProcessor";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        if (List.of(environment.getActiveProfiles()).equals(List.of(PlatformConfigurationGuard.DEV_PROFILE))) {
            return;
        }
        List<String> foreign = new ArrayList<>();
        collect(beanFactory, ToolInputPolicy.class, INPUT_POLICY_BEAN, foreign);
        collect(beanFactory, ToolCallbackAuditPostProcessor.class, AUDIT_POST_PROCESSOR_BEAN, foreign);
        if (!foreign.isEmpty()) {
            throw new IllegalStateException("Bean(s) " + foreign + " replace the platform's tool input policy or "
                    + "audit wrapper. Outside the 'dev' profile they would switch off the bounded-schema rule or "
                    + "the argument validation and audit of every tool; remove them (extra behaviour belongs in a "
                    + "ToolCallInterceptor). See MIGRATION-3.0.md.");
        }
    }

    private static void collect(ConfigurableListableBeanFactory beanFactory, Class<?> type, String platformBean,
                                List<String> foreign) {
        for (String name : beanFactory.getBeanNamesForType(type, true, false)) {
            if (!platformBean.equals(name)) {
                foreign.add(name + " (" + type.getSimpleName() + ")");
            }
        }
    }
}
