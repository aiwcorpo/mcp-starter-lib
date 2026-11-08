package pl.aiwcorpo.mcp.platform.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code jti} store in the memory of one instance: the default of a server (CC-249). Entries live until the token's own validity
 * ends ({@code exp} plus the clock skew), so a restart forgets only tokens that were valid for a minute at most, and the store is
 * bounded. Several instances behind a balancer need {@link RedisJtiReplayStore}: with this store a token could be used once on each.
 */
public final class InMemoryJtiReplayStore implements JtiReplayStore {

    /** Upper bound on remembered tokens; with 60 s tokens it is reached only under abuse (a jti is recorded after the signature is verified). Beyond it the store refuses (fail-closed, 503). */
    private static final int CAPACITY = 100_000;

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();
    private final Clock clock;

    public InMemoryJtiReplayStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean firstUse(String jti, Instant validUntil) throws StoreUnavailableException {
        if (seen.size() >= CAPACITY) {
            Instant now = clock.instant();
            seen.values().removeIf(until -> until.isBefore(now));
            if (seen.size() >= CAPACITY) {
                throw new StoreUnavailableException("in-memory jti store is full", null);
            }
        }
        return seen.putIfAbsent(jti, validUntil) == null;
    }
}
