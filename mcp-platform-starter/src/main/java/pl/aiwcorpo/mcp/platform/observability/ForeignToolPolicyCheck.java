package pl.aiwcorpo.mcp.platform.observability;

import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import pl.aiwcorpo.mcp.platform.auth.InternalJwtFilter;
import pl.aiwcorpo.mcp.platform.auth.InternalJwtVerifier;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;
import pl.aiwcorpo.mcp.platform.guard.RegisteredToolsCheck;
import pl.aiwcorpo.mcp.platform.guard.ToolHintsPostProcessor;
import pl.aiwcorpo.mcp.platform.input.ToolInputPolicy;

/**
 * Outside the {@code dev} profile, refuses to start when a server declares its own {@link ToolInputPolicy},
 * {@link ToolCallbackAuditPostProcessor}, {@link RegisteredToolsCheck} or {@link ToolHintsPostProcessor} bean.
 *
 * <p>These used to be {@code @ConditionalOnMissingBean}: a server bean such as {@code ToolInputPolicy.DEV}
 * switched off the bounded-schema rule, a replacement post-processor dropped the audit wrapper and the
 * argument validation, and a replacement check no longer noticed a tool that skipped the wrapper, all without
 * any message. They are not extension points; what a server may add are {@link ToolCallInterceptor}s.
 *
 * <p>Works on bean definitions by their declared type, so nothing is instantiated. A definition registered under
 * one of the platform's own bean names but not declared by one of the platform's own auto-configurations (a
 * registry post-processor swapping it) is refused as well. It is still only a tripwire: a bean declared as
 * {@code Object}, a {@code BeanPostProcessor} swapping an instance or a bean factory post-processor that runs after
 * this one are not seen. The input policy therefore does not depend on it: the wrapper takes the policy from the
 * active profiles when it is created and reads no bean, and neither the wrapper nor its post-processor can be
 * constructed outside their package.
 */
public class ForeignToolPolicyCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    static final String INPUT_POLICY_BEAN = "toolInputPolicy";
    public static final String AUDIT_POST_PROCESSOR_BEAN = "toolCallbackAuditPostProcessor";
    static final String REGISTERED_TOOLS_CHECK_BEAN = "registeredToolsCheck";
    static final String HINTS_POST_PROCESSOR_BEAN = "toolHintsPostProcessor";

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public static final String CONFIGURATION_GUARD_BEAN = "platformConfigurationGuard";
    public static final String JWT_VERIFIER_BEAN = "internalJwtVerifier";
    public static final String JWT_FILTER_BEAN = "internalJwtFilter";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        List<String> weakened = new ArrayList<>();
        collect(beanFactory, PlatformConfigurationGuard.class, CONFIGURATION_GUARD_BEAN, weakened);
        collect(beanFactory, InternalJwtVerifier.class, JWT_VERIFIER_BEAN, weakened);
        collect(beanFactory, InternalJwtFilter.class, JWT_FILTER_BEAN, weakened);
        if (!weakened.isEmpty()) {
            throw new IllegalStateException("Bean(s) " + weakened + " replace the configuration guard or the internal "
                    + "JWT check. They are not extension points in any profile: the 'dev' relaxations are tied to the "
                    + "guard's loopback check and the JWT check is what every request passes. Replace GatewayKeys or "
                    + "JtiReplayStore instead. See MIGRATION-3.0.md.");
        }
        if (List.of(environment.getActiveProfiles()).equals(List.of(PlatformConfigurationGuard.DEV_PROFILE))) {
            return;
        }
        List<String> foreign = new ArrayList<>();
        collect(beanFactory, ToolInputPolicy.class, INPUT_POLICY_BEAN, foreign);
        collect(beanFactory, ToolCallbackAuditPostProcessor.class, AUDIT_POST_PROCESSOR_BEAN, foreign);
        collect(beanFactory, RegisteredToolsCheck.class, REGISTERED_TOOLS_CHECK_BEAN, foreign);
        collect(beanFactory, ToolHintsPostProcessor.class, HINTS_POST_PROCESSOR_BEAN, foreign);
        if (!foreign.isEmpty()) {
            throw new IllegalStateException("Bean(s) " + foreign + " replace a platform component of the tool "
                    + "pipeline (input policy, audit wrapper, registered-tools check, hints post-processor). Outside the "
                    + "'dev' profile they would switch off the bounded-schema rule, the argument validation and audit of "
                    + "every tool or the check that every tool went through the wrapper; remove them (extra behaviour belongs in a "
                    + "ToolCallInterceptor). See MIGRATION-3.0.md.");
        }
    }

    /** A definition under a platform bean's name that does not come from the platform's own configuration class. */
    private static boolean declaredByThePlatform(BeanDefinition definition) {
        return fromAPlatformConfiguration(definition.getFactoryBeanName())
                || fromAPlatformConfiguration(definition.getBeanClassName());
    }

    private static boolean fromAPlatformConfiguration(String className) {
        return className != null && className.startsWith("pl.aiwcorpo.mcp.platform.")
                && className.endsWith("AutoConfiguration");
    }

    private static void collect(ConfigurableListableBeanFactory beanFactory, Class<?> type, String platformBean,
                                List<String> foreign) {
        for (String name : beanFactory.getBeanNamesForType(type, true, false)) {
            if (!platformBean.equals(name)) {
                foreign.add(name + " (" + type.getSimpleName() + ")");
            } else if (!declaredByThePlatform(beanFactory.getBeanDefinition(name))) {
                foreign.add(name + " (" + type.getSimpleName() + ", definition replaced)");
            }
        }
    }
}
