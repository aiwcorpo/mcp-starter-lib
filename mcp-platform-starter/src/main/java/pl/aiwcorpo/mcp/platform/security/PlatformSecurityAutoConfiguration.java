package pl.aiwcorpo.mcp.platform.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import pl.aiwcorpo.mcp.platform.autoconfigure.AuthMode;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;

/**
 * HTTP baseline: configuration check at startup, {@link CredentialHeaderFilter} (backend credential, C3),
 * {@link SecurityHeadersFilter} and {@link CredentialTransportGuard}.
 */
@AutoConfiguration
@EnableConfigurationProperties(McpPlatformProperties.class)
public class PlatformSecurityAutoConfiguration {

    /**
     * Not {@code @ConditionalOnMissingBean}: the dev relaxations are bound to the loopback check made here.
     * {@code @Lazy(false)}: the checks run in {@code afterPropertiesSet}, and with
     * {@code spring.main.lazy-initialization=true} a bean nobody depends on is never created, so a server with its own
     * {@link CredentialHeaderFilter} (the only eager dependant) would start without any of them.
     */
    @Bean
    @Lazy(false)
    PlatformConfigurationGuard platformConfigurationGuard(McpPlatformProperties props, Environment environment) {
        return new PlatformConfigurationGuard(props, environment);
    }

    @Bean
    @ConditionalOnMissingBean
    CredentialHeaderFilter credentialHeaderFilter(McpPlatformProperties props, PlatformConfigurationGuard checked) {
        return new CredentialHeaderFilter(props.system(),
                props.getAuthMode() == AuthMode.DEV_PAT ? props.getDevPat().getHeaderPrefix() : null);
    }

    /** Eager for the same reason as the configuration guard: it is what binds {@code dev} to the loopback interface. */
    @Bean
    @Lazy(false)
    DevListenAddressCheck devListenAddressCheck() {
        return new DevListenAddressCheck();
    }

    @Bean
    @ConditionalOnMissingBean
    SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter();
    }

    /** Eager for the same reason as the configuration guard; nothing depends on it. */
    @Bean
    @Lazy(false)
    @ConditionalOnMissingBean
    CredentialTransportGuard credentialTransportGuard(Environment environment, McpPlatformProperties props) {
        return new CredentialTransportGuard(environment, props.isRequireSyncTransport());
    }
}
