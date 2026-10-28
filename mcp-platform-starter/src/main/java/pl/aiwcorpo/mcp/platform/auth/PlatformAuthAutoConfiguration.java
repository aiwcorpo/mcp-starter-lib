package pl.aiwcorpo.mcp.platform.auth;

import java.net.URI;
import java.time.Clock;
import java.util.Set;
import javax.net.ssl.SSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.observability.AuditLogger;

/**
 * The internal JWT (C2): gateway keys, {@code jti} store, verifier and {@link InternalJwtFilter}. Always on;
 * there is no switch that serves requests without it. The key source ({@link GatewayKeys}) and the {@code jti}
 * store ({@link JtiReplayStore}) are {@code @ConditionalOnMissingBean} extension points; the verifier and the
 * filter are not, because replacing them replaces the check itself ({@code ForeignToolPolicyCheck} refuses
 * such a bean).
 */
@AutoConfiguration
@EnableConfigurationProperties(McpPlatformProperties.class)
public class PlatformAuthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    GatewayKeys gatewayKeys(McpPlatformProperties props, ObjectProvider<SslBundles> sslBundles) {
        McpPlatformProperties.InternalJwt jwt = props.getInternalJwt();
        SSLContext sslContext = null;
        if (jwt.getJwksSslBundle() != null && !jwt.getJwksSslBundle().isBlank()) {
            sslContext = sslBundles.getObject().getBundle(jwt.getJwksSslBundle()).createSslContext();
        }
        URI uri = URI.create(jwt.getJwksUri() == null ? "" : jwt.getJwksUri());
        return new JwksGatewayKeys(new HttpJwksFetcher(uri, sslContext, jwt.getJwksConnectTimeout(), jwt.getJwksReadTimeout()),
                Clock.systemUTC(), jwt.getJwksCacheTtl(), jwt.getJwksMaxStale(), jwt.getJwksRefreshMinInterval());
    }

    @Bean
    @ConditionalOnMissingBean
    JtiReplayStore jtiReplayStore(McpPlatformProperties props, ObjectProvider<StringRedisTemplate> redis) {
        if (props.getInternalJwt().getReplayStore() == McpPlatformProperties.ReplayStore.MEMORY) {
            return new InMemoryJtiReplayStore(Clock.systemUTC());
        }
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("internal-jwt.replay-store=redis needs Redis (spring.data.redis.*)");
        }
        return new RedisJtiReplayStore(template, props.getServerId(), Clock.systemUTC());
    }

    @Bean
    InternalJwtVerifier internalJwtVerifier(McpPlatformProperties props, GatewayKeys keys, JtiReplayStore replayStore) {
        McpPlatformProperties.InternalJwt jwt = props.getInternalJwt();
        return new InternalJwtVerifier(
                new InternalJwtVerifier.Settings(jwt.getIssuer(), props.getServerId(), jwt.getClockSkew(),
                        jwt.getMaxLifetime(), jwt.isAllowTestKeys()),
                keys, replayStore, Clock.systemUTC());
    }

    @Bean
    InternalJwtFilter internalJwtFilter(McpPlatformProperties props, InternalJwtVerifier verifier, AuditLogger audit) {
        return new InternalJwtFilter(verifier, props.getInternalJwt().getUnauthenticatedPaths(),
                props.getInternalJwt().getMaxRequestBody().toBytes(), props.getMtls().isRequired(),
                Set.copyOf(props.getMtls().getAllowedClientNames()), audit);
    }
}
