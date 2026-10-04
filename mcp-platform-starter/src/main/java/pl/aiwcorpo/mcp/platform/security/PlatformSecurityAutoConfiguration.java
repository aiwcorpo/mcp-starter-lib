package pl.aiwcorpo.mcp.platform.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;

/**
 * HTTP baseline for every MCP server (auto-configured by the starter).
 *
 * <p>Authentication to backends is delegated to per-request PATs supplied
 * by each caller (bring-your-own-token), so every user acts as themselves in the backend. This
 * config wires:
 * <ul>
 *   <li>{@link PatForwardingFilter} — pulls {@code X-PAT-*} headers into {@link CredentialContext}
 *       so outbound adapters can forward each caller's own token.</li>
 *   <li>{@link SecurityHeadersFilter} — HSTS + nosniff + frame-deny.</li>
 * </ul>
 *
 * <p>This configuration covers credentials for the <em>backends</em>. Who the caller is, and what
 * they may do on this server, is the tool guard's job since 2.0.0 — see
 * {@code PlatformGuardAutoConfiguration}. Because PATs pass through the server in request headers,
 * NEVER log them and always run over TLS. This is a deliberate interim model — plan to move to token-exchange / a secret
 * broker later. Beans are {@code @ConditionalOnMissingBean} so a server may override.
 */
@AutoConfiguration
@EnableConfigurationProperties(McpPlatformProperties.class)
public class PlatformSecurityAutoConfiguration {

    /** PAT mode only: in OAuth mode {@code X-PAT-*} headers are not a credential and are not read. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "aiwcorpo.mcp.platform", name = "auth-mode", havingValue = "pat")
    PatForwardingFilter patForwardingFilter(McpPlatformProperties props) {
        return new PatForwardingFilter(props.getPatHeaderPrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter();
    }

    /**
     * Fails the context when the transport cannot carry per-request credentials at all. Cheap
     * insurance: the alternative to failing at startup is a server that answers every call as if no
     * caller had ever supplied a token.
     */
    @Bean
    @ConditionalOnMissingBean
    CredentialTransportGuard credentialTransportGuard(Environment environment,
                                                      McpPlatformProperties props) {
        return new CredentialTransportGuard(environment, props.isRequireSyncTransport());
    }
}
