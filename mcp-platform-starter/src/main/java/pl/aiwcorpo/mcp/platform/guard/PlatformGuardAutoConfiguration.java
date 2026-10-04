package pl.aiwcorpo.mcp.platform.guard;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import pl.aiwcorpo.mcp.platform.autoconfigure.AuthMode;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;

/**
 * The tool guard: identity, privilege and human confirmation in front of every tool
 * ({@code aiwcorpo.mcp.platform.guard.*}). On by default; {@code guard.enabled=false} returns the
 * server to the 1.x behaviour (audit and error sanitization only, no inbound identity).
 *
 * <p>Every bean is {@code @ConditionalOnMissingBean}. A real deployment configures {@code guard.oidc}
 * (the identity provider) and replaces {@link ConfirmationCodeSender} (deliver codes to people
 * instead of the log).
 */
@AutoConfiguration
@ConditionalOnClass(ToolCallback.class)
@ConditionalOnProperty(prefix = "aiwcorpo.mcp.platform.guard", name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(McpPlatformProperties.class)
public class PlatformGuardAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformGuardAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    GuardPolicyStore guardPolicyStore(McpPlatformProperties props, ResourceLoader resourceLoader) {
        McpPlatformProperties.Guard guard = props.getGuard();
        String location = guard.getPolicyFile();
        Resource resource = location == null || location.isBlank() ? null : resourceLoader.getResource(location);
        return new GuardPolicyStore(resource, guard.getReloadInterval().toMillis());
    }

    /**
     * OIDC when {@code guard.oidc} is configured, otherwise a resolver under which everyone is
     * anonymous. Building the decoder is deferred to the first token, so the server starts even if
     * the provider is not reachable yet.
     */
    @Bean
    @ConditionalOnMissingBean
    IdentityResolver identityResolver(GuardPolicyStore policies, McpPlatformProperties props) {
        McpPlatformProperties.Oidc oidc = props.getGuard().getOidc();
        if (props.getAuthMode() == AuthMode.PAT) {
            if (oidc.isConfigured()) {
                LOG.warn("event=oidc_ignored detail=\"auth-mode is pat: guard.oidc is configured but not used\"");
            }
            LOG.info("event=auth_mode mode=pat detail=\"callers are identified by their X-PAT-* token only; "
                    + "this server cannot verify it, the backend does\"");
            return new PatIdentityResolver(policies);
        }
        if (!oidc.isConfigured()) {
            LOG.warn("event=identity_provider_missing detail=\"aiwcorpo.mcp.platform.guard.oidc is not configured: "
                    + "every caller is anonymous and bearer tokens are rejected\"");
            return new NoIdentityProviderResolver();
        }
        if (oidc.getAudiences().isEmpty()) {
            LOG.warn("event=oidc_audience_unchecked detail=\"guard.oidc.audiences is empty: a token this provider "
                    + "issued for ANY application will be accepted here. Set it to this server's identifier.\"");
        }
        return new OidcIdentityResolver(new SupplierJwtDecoder(() -> jwtDecoder(oidc)), policies,
                oidc.getSubjectClaim(), oidc.getRoleClaims());
    }

    private static JwtDecoder jwtDecoder(McpPlatformProperties.Oidc oidc) {
        boolean hasIssuer = oidc.getIssuerUri() != null && !oidc.getIssuerUri().isBlank();
        boolean hasJwks = oidc.getJwkSetUri() != null && !oidc.getJwkSetUri().isBlank();
        NimbusJwtDecoder decoder = hasJwks
                ? NimbusJwtDecoder.withJwkSetUri(oidc.getJwkSetUri())
                        .jwsAlgorithms(accepted -> oidc.getJwsAlgorithms()
                                .forEach(name -> accepted.add(SignatureAlgorithm.from(name))))
                        .build()
                : NimbusJwtDecoder.withIssuerLocation(oidc.getIssuerUri()).build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        // expiry / not-before always; issuer whenever we know it
        validators.add(hasIssuer ? JwtValidators.createDefaultWithIssuer(oidc.getIssuerUri()) : JwtValidators.createDefault());
        if (!oidc.getAudiences().isEmpty()) {
            Set<String> accepted = Set.copyOf(oidc.getAudiences());
            validators.add(new JwtClaimValidator<Collection<String>>("aud",
                    audience -> audience != null && audience.stream().anyMatch(accepted::contains)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    @Bean
    @ConditionalOnMissingBean
    IdentityFilter identityFilter(IdentityResolver resolver, GuardPolicyStore policies, McpPlatformProperties props) {
        boolean oauth = props.getAuthMode() == AuthMode.OAUTH;
        String issuer = props.getGuard().getOidc().getIssuerUri();
        return new IdentityFilter(resolver, policies, oauth && issuer != null && !issuer.isBlank(), oauth);
    }

    /** Answers only when an issuer is configured: the metadata exists to name the authorization server. */
    @Bean
    @ConditionalOnMissingBean
    ProtectedResourceMetadataFilter protectedResourceMetadataFilter(McpPlatformProperties props, Environment env) {
        McpPlatformProperties.Oidc oidc = props.getGuard().getOidc();
        return new ProtectedResourceMetadataFilter(
                props.getAuthMode() == AuthMode.OAUTH ? oidc.getIssuerUri() : null, oidc.getResourceUri(),
                env.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/mcp"));
    }

    @Bean
    @ConditionalOnMissingBean
    ToolRiskRegistry toolRiskRegistry(McpPlatformProperties props) {
        return new ToolRiskRegistry(props.getGuard().getUnclassified());
    }

    @Bean
    @ConditionalOnMissingBean
    ConfirmationCodeSender confirmationCodeSender() {
        return new LoggingConfirmationCodeSender();
    }

    @Bean
    @ConditionalOnMissingBean
    ConfirmationService confirmationService(ConfirmationCodeSender sender) {
        return new ConfirmationService(sender);
    }

    @Bean
    @ConditionalOnMissingBean
    ConfirmationPrompter confirmationPrompter() {
        return new ElicitationConfirmationPrompter();
    }

    @Bean
    @ConditionalOnMissingBean
    ToolGuard toolGuard(GuardPolicyStore policies, ToolRiskRegistry risks, ConfirmationService confirmations,
                        ConfirmationPrompter prompter) {
        return new ToolGuard(policies, risks, confirmations, prompter);
    }

    /** Static for the same reason as the audit post-processor: do not drag this configuration in early. */
    @Bean
    @ConditionalOnMissingBean
    static ToolHintsPostProcessor toolHintsPostProcessor(ObjectProvider<ToolGuard> toolGuard) {
        return new ToolHintsPostProcessor(toolGuard);
    }

    /**
     * The built-in {@code platform_whoami} tool. A {@code ToolCallback} bean, not a second
     * {@code ToolCallbackProvider}: servers inject their own provider by type, and a second bean of
     * that type would make that injection ambiguous.
     */
    @Bean
    @ConditionalOnProperty(prefix = "aiwcorpo.mcp.platform.guard", name = "whoami-tool", havingValue = "true",
            matchIfMissing = true)
    ToolCallback platformWhoAmITool(ToolGuard guard) {
        return new WhoAmITool(guard);
    }
}
