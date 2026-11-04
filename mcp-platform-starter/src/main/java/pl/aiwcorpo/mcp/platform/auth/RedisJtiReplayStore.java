package pl.aiwcorpo.mcp.platform.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * {@code jti} store in Redis: one {@code SET jti:<serverId>:<jti> 1 NX PX <ms>} per token.
 *
 * <p>A single command because the Redis user of a server in the dev stack may run only an explicit list
 * of commands on its own prefix ({@code SET} yes; {@code EVAL}, {@code SCAN}, {@code KEYS} no), and because
 * {@code SET NX} is already atomic: no read-then-write window in which two copies of a token both pass.
 */
public final class RedisJtiReplayStore implements JtiReplayStore {

    private static final Duration MINIMUM_TTL = Duration.ofSeconds(1);

    private final StringRedisTemplate redis;
    private final String keyPrefix;
    private final Clock clock;

    /** @param serverId the {@code aud} of this server; keys are {@code jti:<serverId>:<jti>} */
    public RedisJtiReplayStore(StringRedisTemplate redis, String serverId, Clock clock) {
        this.redis = redis;
        this.keyPrefix = "jti:" + serverId + ":";
        this.clock = clock;
    }

    @Override
    public boolean firstUse(String jti, Instant validUntil) throws StoreUnavailableException {
        Duration ttl = Duration.between(clock.instant(), validUntil);
        if (ttl.compareTo(MINIMUM_TTL) < 0) {
            ttl = MINIMUM_TTL;
        }
        Boolean stored;
        try {
            stored = redis.opsForValue().setIfAbsent(keyPrefix + jti, "1", ttl);
        } catch (RuntimeException ex) {
            throw new StoreUnavailableException("Redis did not record the jti", ex);
        }
        if (stored == null) {
            // Only in a pipeline or transaction, which this template is not in; treat as no answer.
            throw new StoreUnavailableException("Redis returned no answer for SET NX", null);
        }
        return stored;
    }
}
