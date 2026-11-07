package pl.aiwcorpo.mcp.platform.server;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Refuses to start when a server declares its own {@code @Primary} {@link McpSyncServerCustomizer}.
 *
 * <p>Spring AI would take that bean instead of the starter's, so the starter's settings
 * ({@code immediateExecution(true)}, {@code validateToolInputs(false)}) would not be applied: calls would run
 * without a credential and schema violations would bypass the audit. Without {@code @Primary} the server's
 * customizer is simply composed with the starter's. Works on bean definitions, so nothing is instantiated.
 */
public class ForeignPrimaryCustomizerCheck implements BeanFactoryPostProcessor {

    public static final String STARTER_BEAN = "platformMcpServerCustomizer";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        List<String> foreign = new ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(McpSyncServerCustomizer.class, true, false)) {
            if (!STARTER_BEAN.equals(name) && beanFactory.containsBeanDefinition(name)
                    && beanFactory.getBeanDefinition(name).isPrimary()) {
                foreign.add(name);
            }
        }
        if (!foreign.isEmpty()) {
            throw new IllegalStateException("McpSyncServerCustomizer bean(s) " + foreign + " are @Primary and would "
                    + "replace the starter's customizer, which sets immediateExecution(true) (tools on the request "
                    + "thread, where the credential is) and validateToolInputs(false) (arguments validated by the "
                    + "starter, with C10 errors and C8 audit). Remove @Primary: a customizer without it is applied "
                    + "together with the starter's.");
        }
    }
}
