package pl.aiwcorpo.mcp.platform.policy;

import io.micrometer.core.instrument.MeterRegistry;
import java.security.GeneralSecurityException;
import java.util.List;
import javax.net.ssl.SSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.observability.AuditLogger;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolvers;

/**
 * The policies of the server, the policy data of the gateway and the resource checks. The engine and {@link ResourcePolicy} are not
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
     * Eager as well: the first request for the policy data is made at start, so a server that can be served is ready when it starts.
     * The server's own certificate goes to the gateway as the client certificate of mTLS.
     */
    @Bean(destroyMethod = "close")
    @Lazy(false)
    PolicyEngine policyEngine(McpPlatformProperties props, ServerPolicies policies, ObjectProvider<SslBundles> sslBundles,
                              ObjectProvider<MeterRegistry> meters, ObjectProvider<PlatformConfigurationGuard> guard,
                              Environment environment) throws GeneralSecurityException {
        guard.getIfAvailable();
        McpPlatformProperties.PolicyData config = props.getPolicyData();
        // server.ssl.enabled=false is what the dev profile forces: the bundle of a server that does not use TLS is not the one to use.
        String bundleName = config.getSslBundle() != null && !config.getSslBundle().isBlank() ? config.getSslBundle()
                : "false".equalsIgnoreCase(environment.getProperty("server.ssl.enabled")) ? null
                : environment.getProperty("server.ssl.bundle");
        SSLContext sslContext = null;
        if (bundleName != null && !bundleName.isBlank()) {
            sslContext = GatewayTls.sslContext(sslBundles.getObject().getBundle(bundleName), config.getTlsName());
        }
        requireSslContext(sslContext, environment);
        return PolicyEngine.create(config, policies, sslContext, meters.getIfAvailable());
    }

    /**
     * Outside the {@code dev} profile the connection to the gateway has the server's own certificate and the internal CA, or the start fails:
     * never the JVM default trust and no client certificate (the gateway serves the policy data only to a server that proves who it is).
     */
    static void requireSslContext(SSLContext sslContext, Environment environment) {
        if (sslContext == null && !java.util.List.of(environment.getActiveProfiles()).equals(java.util.List.of(PlatformConfigurationGuard.DEV_PROFILE))) {
            throw new IllegalStateException("aiwcorpo.mcp.platform.policy-data: no SSL context for the connection to the gateway. Outside the 'dev' "
                    + "profile the policy data is read over mTLS with the server's own certificate: set policy-data.ssl-bundle (or server.ssl.bundle, "
                    + "with server.ssl.enabled not false) to a Spring SSL bundle that holds the key and certificate of this server and the internal CA");
        }
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
