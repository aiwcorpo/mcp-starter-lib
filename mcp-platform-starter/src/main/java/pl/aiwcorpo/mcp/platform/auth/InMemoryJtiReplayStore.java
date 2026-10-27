package pl.aiwcorpo.mcp.platform.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code jti} store in the memory of one instance. Only for the {@code dev} profile and tests: a second
 * instance, or a restart, would accept a token again.
 */
public final class InMemoryJtiReplayStore implements JtiReplayStore {

    /** Upper bound on remembered tokens; with 60 s tokens it is reached only under abuse. */
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
