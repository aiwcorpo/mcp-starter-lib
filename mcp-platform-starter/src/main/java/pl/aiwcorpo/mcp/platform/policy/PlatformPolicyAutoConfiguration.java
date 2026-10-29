package pl.aiwcorpo.mcp.platform.policy;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;
import org.springframework.core.env.Environment;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.observability.AuditLogger;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolvers;

/**
 * The policy engine and the resource checks. The engine and {@link ResourcePolicy} are not extension points
 * ({@code ForeignToolPolicyCheck}): replacing them replaces the check itself. A server contributes
 * {@link ResourceResolver} beans.
 */
@AutoConfiguration
@EnableConfigurationProperties(McpPlatformProperties.class)
public class PlatformPolicyAutoConfiguration {

    /**
     * Eager: a server without a signed policy must not finish starting. The configuration guard is created by the first line of the
     * method, before anything is pulled: it refuses test keys and plain http outside dev and devstack. The order is stated in
     * code, not left to bean order. {@code getIfAvailable} and not {@code getObject}: the guard is missing only in a Spring test
     * context that leaves the platform out on purpose; {@code PlatformPresenceCheck} refuses its absence everywhere else.
     */
    @Bean(destroyMethod = "close")
    @Lazy(false)
    PolicyEngine policyEngine(McpPlatformProperties props, ObjectProvider<SslBundles> sslBundles,
                              ObjectProvider<MeterRegistry> meters, ObjectProvider<PlatformConfigurationGuard> guard, Environment environment)
            throws IOException, GeneralSecurityException {
        guard.getIfAvailable();    // creates the guard first: it throws before anything is pulled
        McpPlatformProperties.Policy policy = props.getPolicy();
        SSLContext sslContext = null;
        if (policy.getSslBundle() != null && !policy.getSslBundle().isBlank()) {
            sslContext = sslBundles.getObject().getBundle(policy.getSslBundle()).createSslContext();
        }
        return PolicyEngine.create(policy, sslContext, meters.getIfAvailable(),
                PlatformConfigurationGuard.devstackProfile(environment));
    }

    @Bean
    ResourceResolvers resourceResolvers(ObjectProvider<ResourceResolver<?>> resolvers) {
        return new ResourceResolvers(resolvers.orderedStream().toList());
    }

    @Bean
    ResourcePolicy resourcePolicy(PolicyEngine engine, ResourceResolvers resolvers, ToolRiskRegistry registry,
                                  AuditLogger audit, McpPlatformProperties props) {
        return new ResourcePolicy(engine, resolvers, registry, audit, props.getServerId(), props.system(), props.effectiveCredentialType(),
                props.getTools().getMaxFilteredElements());
    }
}
