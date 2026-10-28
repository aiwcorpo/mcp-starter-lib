package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.util.List;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import pl.aiwcorpo.mcp.platform.observability.ForeignToolPolicyCheck;
import pl.aiwcorpo.mcp.platform.server.ForeignPrimaryCustomizerCheck;

/**
 * Outside the {@code dev} profile, refuses to start when the beans that guard the server are not defined at all:
 * the configuration guard, the internal JWT verifier and filter, the audit post-processor and the MCP server
 * customizer (without it the SDK validates the input itself, before the audit wrapper, and the audit and error
 * contracts do not hold). They are missing when
 * their auto-configurations are excluded, which {@code @SpringBootApplication(exclude = ...)} does without a property
 * that {@code PlatformDefaultsEnvironmentPostProcessor} could see. Registered through {@code spring.factories}
 * (an auto-configuration cannot check its own absence), so it works whatever the application excludes.
 *
 * <p>Not applied in a Spring test context: a slice test (for example a web layer test of one controller) leaves
 * the platform out on purpose.
 */
public class PlatformPresenceCheck implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final List<String> REQUIRED_BEANS = List.of(ForeignToolPolicyCheck.CONFIGURATION_GUARD_BEAN,
            ForeignToolPolicyCheck.JWT_VERIFIER_BEAN, ForeignToolPolicyCheck.JWT_FILTER_BEAN,
            ForeignToolPolicyCheck.AUDIT_POST_PROCESSOR_BEAN, ForeignPrimaryCustomizerCheck.STARTER_BEAN);

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        context.addBeanFactoryPostProcessor(new Check(context.getEnvironment()));
    }

    private record Check(Environment environment) implements BeanFactoryPostProcessor {

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            if (List.of(environment.getActiveProfiles()).equals(List.of(PlatformConfigurationGuard.DEV_PROFILE)) || DevProfileEnvironment.inSpringTestContext()) {
                return;
            }
            List<String> missing = REQUIRED_BEANS.stream().filter(name -> !beanFactory.containsBeanDefinition(name)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("The platform's beans " + missing + " are not defined: its "
                        + "auto-configurations are excluded (spring.autoconfigure.exclude or exclude on "
                        + "@SpringBootApplication). Without them the server runs without the configuration guard or "
                        + "the internal JWT check; remove the exclusion.");
            }
        }
    }
}
