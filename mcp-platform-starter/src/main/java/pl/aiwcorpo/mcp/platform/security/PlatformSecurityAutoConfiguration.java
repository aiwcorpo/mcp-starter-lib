package pl.aiwcorpo.mcp.platform.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
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

    @Bean
    @ConditionalOnMissingBean
    PlatformConfigurationGuard platformConfigurationGuard(McpPlatformProperties props, Environment environment) {
        return new PlatformConfigurationGuard(props, environment);
    }

    @Bean
    @ConditionalOnMissingBean
    CredentialHeaderFilter credentialHeaderFilter(McpPlatformProperties props, PlatformConfigurationGuard checked) {
        return new CredentialHeaderFilter(props.system(),
                props.getAuthMode() == AuthMode.DEV_PAT ? props.getDevPat().getHeaderPrefix() : null);
    }

    @Bean
    @ConditionalOnMissingBean
    SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter();
    }

    @Bean
    @ConditionalOnMissingBean
    CredentialTransportGuard credentialTransportGuard(Environment environment, McpPlatformProperties props) {
        return new CredentialTransportGuard(environment, props.isRequireSyncTransport());
    }
}
