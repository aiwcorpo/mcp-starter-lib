package pl.aiwcorpo.mcp.platform.paging;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;

/** The {@link CursorCodec} bean of paged tools. */
@AutoConfiguration
@EnableConfigurationProperties(McpPlatformProperties.class)
public class PlatformPagingAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformPagingAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    CursorCodec cursorCodec(McpPlatformProperties props) {
        McpPlatformProperties.Paging paging = props.getPaging();
        byte[] key;
        if (paging.getCursorKey() == null || paging.getCursorKey().isBlank()) {
            key = new byte[32];
            new SecureRandom().nextBytes(key);
            LOG.warn("aiwcorpo.mcp.platform.paging.cursor-key is not set: cursors are valid only on this instance until it restarts");
        } else {
            try {
                key = Base64.getDecoder().decode(paging.getCursorKey().strip());
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("aiwcorpo.mcp.platform.paging.cursor-key is not Base64", ex);
            }
            if (key.length < 32) {
                throw new IllegalStateException("aiwcorpo.mcp.platform.paging.cursor-key must decode to at least 32 bytes");
            }
        }
        return new CursorCodec(key, paging.getCursorValidity(), Clock.systemUTC());
    }
}
