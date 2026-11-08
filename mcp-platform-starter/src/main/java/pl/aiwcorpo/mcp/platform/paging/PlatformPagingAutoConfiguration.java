package pl.aiwcorpo.mcp.platform.paging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;

/**
 * The {@link CursorCodec} bean of paged tools. Its key is {@code MCP_PAGING_CURSOR_KEY} when given. Otherwise it is generated once into
 * {@code paging-cursor.key} of the state directory ({@code MCP_STATE_DIR}) and reused at every start, so cursors survive a restart of one
 * instance. Several instances behind a balancer must all have the same explicit key: each would otherwise generate its own.
 */
@AutoConfiguration
@EnableConfigurationProperties(McpPlatformProperties.class)
public class PlatformPagingAutoConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformPagingAutoConfiguration.class);

    static final String KEY_FILE = "paging-cursor.key";

    @Bean
    @ConditionalOnMissingBean
    CursorCodec cursorCodec(McpPlatformProperties props, Environment environment) {
        McpPlatformProperties.Paging paging = props.getPaging();
        String serverId = serverIdOf(props);
        if (paging.getCursorKey() != null && !paging.getCursorKey().isBlank()) {
            byte[] key;
            try {
                key = Base64.getDecoder().decode(paging.getCursorKey().strip());
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("aiwcorpo.mcp.platform.paging.cursor-key (MCP_PAGING_CURSOR_KEY) is not Base64", ex);
            }
            if (key.length < 32) {
                throw new IllegalStateException("aiwcorpo.mcp.platform.paging.cursor-key (MCP_PAGING_CURSOR_KEY) must decode to at least 32 bytes");
            }
            return new CursorCodec(key, paging.getCursorValidity(), Clock.systemUTC(), serverId);
        }
        boolean dev = List.of(environment.getActiveProfiles()).equals(List.of(PlatformConfigurationGuard.DEV_PROFILE));
        if (!dev) {
            byte[] key = fromStateDirectory(Path.of(props.getStateDir()).resolve(KEY_FILE));
            if (key != null) {
                return new CursorCodec(key, paging.getCursorValidity(), Clock.systemUTC(), serverId);
            }
        }
        // The dev profile, or a state directory that cannot be written: a key of this process, which the codec reports at its first use.
        return CursorCodec.withGeneratedKey(randomKey(), paging.getCursorValidity(), Clock.systemUTC(), serverId);
    }

    /** The key in {@code file}, made when it is not there yet; {@code null} (with a warning) when the directory cannot be written. */
    static byte[] fromStateDirectory(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                byte[] key = Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).strip());
                if (key.length >= 32) {
                    LOG.info("event=paging_cursor_key source=state-dir file={}", file);
                    return key;
                }
                LOG.warn("event=paging_cursor_key_replaced file={} detail=\"the stored key is shorter than 32 bytes\"", file);
            }
            byte[] key = randomKey();
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), KEY_FILE, ".part");
            try {
                try {
                    Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ex) {
                    // A file system without POSIX permissions: the directory's own access rules apply.
                }
                Files.writeString(temporary, Base64.getEncoder().encodeToString(key) + "\n", StandardCharsets.US_ASCII);
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            LOG.info("event=paging_cursor_key source=state-dir file={} detail=\"generated and kept for the next start\"", file);
            return key;
        } catch (IOException | IllegalArgumentException ex) {
            LOG.warn("event=paging_cursor_key_not_kept file={} detail=\"the state directory cannot be used ({}); a key of this process is used, so cursors "
                    + "do not survive a restart. Mount a volume at MCP_STATE_DIR, or set MCP_PAGING_CURSOR_KEY\"", file, ex.getClass().getSimpleName());
            return null;
        }
    }

    private static byte[] randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    private static String serverIdOf(McpPlatformProperties props) {
        return props.getServerId() == null ? "" : props.getServerId();
    }
}
