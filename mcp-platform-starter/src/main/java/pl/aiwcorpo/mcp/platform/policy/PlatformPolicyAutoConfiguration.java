package pl.aiwcorpo.mcp.platform.policy;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.observability.AuditLogger;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolvers;

/**
 * The policies of the server, the policy data the gateway pushes, and the resource checks. The engine and {@link ResourcePolicy} are not
 * extension points ({@code ForeignToolPolicyCheck}): replacing them replaces the check itself. A server contributes
 * {@link ResourceResolver} beans and the files of {@code mcp/policies/}.
 *
 * <p>Not active in the {@code adapter} receiver mode: an adapter has no policies of the platform (its manifest has neither
 * {@code resourceTypes} nor {@code policies}).
 */
@AutoConfiguration
@EnableConfigurationProperties(McpPlatformProperties.class)
@ConditionalOnProperty(prefix = "aiwcorpo.mcp.platform.internal-jwt", name = "receiver-mode", havingValue = "native", matchIfMissing = true)
public class PlatformPolicyAutoConfiguration {

    /**
     * The policies of the application's own code (the classpath root of its {@code @SpringBootApplication} class), validated. Eager: a server whose policies the gateway would reject must not finish starting. The
     * configuration guard is created by the first line of the method, before anything is read. {@code getIfAvailable} and not
     * {@code getObject}: the guard is missing only in a Spring test context that leaves the platform out on purpose;
     * {@code PlatformPresenceCheck} refuses its absence everywhere else.
     */
    @Bean
    @Lazy(false)
    ServerPolicies serverPolicies(ObjectProvider<PlatformConfigurationGuard> guard, org.springframework.beans.factory.ListableBeanFactory beans) {
        guard.getIfAvailable();
        List<Class<?>> application = new java.util.ArrayList<>();
        for (String name : beans.getBeanNamesForAnnotation(org.springframework.boot.SpringBootConfiguration.class)) {
            Class<?> type = beans.getType(name);
            if (type != null) {
                application.add(type);
            }
        }
        return ServerPolicies.load(Thread.currentThread().getContextClassLoader(), application);
    }

    /**
     * Eager as well, so that a missing configuration shows at start. The engine holds nothing until the gateway pushes policy data to
     * {@link PolicyDataEndpoint}; until then every call is refused as {@code policy_data_stale}.
     */
    @Bean
    @Lazy(false)
    PolicyEngine policyEngine(ServerPolicies policies, ObjectProvider<MeterRegistry> meters, ObjectProvider<PlatformConfigurationGuard> guard) {
        guard.getIfAvailable();
        return PolicyEngine.create(policies, meters.getIfAvailable());
    }

    @Bean
    PolicyDataEndpoint policyDataEndpoint(PolicyEngine engine) {
        return new PolicyDataEndpoint(engine);
    }

    @Bean
    PolicyDataStaleFilter policyDataStaleFilter(PolicyEngine engine) {
        return new PolicyDataStaleFilter(engine);
    }

    @Bean
    ResourceResolvers resourceResolvers(ObjectProvider<ResourceResolver<?>> resolvers) {
        return new ResourceResolvers(resolvers.orderedStream().toList());
    }

    @Bean
    ResourcePolicy resourcePolicy(PolicyEngine engine, ResourceResolvers resolvers, ToolRiskRegistry registry,
                                  AuditLogger audit, McpPlatformProperties props) {
        return new ResourcePolicy(engine, resolvers, registry, audit, props.getServerId(), props.system(), props.getManifest().getAcceptedCredentialTypes(),
                props.getTools().getMaxFilteredElements());
    }
}
