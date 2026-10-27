package pl.aiwcorpo.mcp.platform.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gateway's JWKS, fetched on demand and cached.
 *
 * <ul>
 *   <li>A copy older than {@code cacheTtl} is fetched again before use.</li>
 *   <li>An unknown {@code kid} triggers a fetch (key rotation: the gateway publishes a new key at least
 *       24 h before it signs with it), at most once per {@code refreshMinInterval}, so random {@code kid}s
 *       cannot turn every request into a call to the gateway.</li>
 *   <li>When fetching fails, the last copy is used until it is {@code maxStale} old; after that, and when
 *       nothing was ever fetched, {@link #find} throws and the request is refused (fail-closed).</li>
 * </ul>
 * Only EC P-256 keys with a {@code kid}, usable for signatures and not restricted to another algorithm,
 * are kept; anything else in the set is ignored.
 */
public final class JwksGatewayKeys implements GatewayKeys {

    private static final Logger LOG = LoggerFactory.getLogger(JwksGatewayKeys.class);

    /** Returns the JWKS document; any exception counts as "gateway unreachable". */
    @FunctionalInterface
    public interface JwksFetcher {
        String fetch() throws Exception;
    }

    private record Snapshot(Map<String, ECKey> keys, Instant fetchedAt) {
    }

    private final JwksFetcher fetcher;
    private final Clock clock;
    private final Duration cacheTtl;
    private final Duration maxStale;
    private final Duration refreshMinInterval;

    private final ReentrantLock refreshing = new ReentrantLock();
    private volatile Snapshot snapshot;
    private Instant lastAttempt;

    public JwksGatewayKeys(JwksFetcher fetcher, Clock clock, Duration cacheTtl, Duration maxStale,
                           Duration refreshMinInterval) {
        this.fetcher = fetcher;
        this.clock = clock;
        this.cacheTtl = cacheTtl;
        this.maxStale = maxStale.compareTo(cacheTtl) < 0 ? cacheTtl : maxStale;
        this.refreshMinInterval = refreshMinInterval;
    }

    /** A fixed key set, e.g. the test JWKS of mcp-contracts in tests. */
    public static JwksGatewayKeys of(String jwksJson) {
        Duration forever = Duration.ofDays(365L * 100);
        return new JwksGatewayKeys(() -> jwksJson, Clock.systemUTC(), forever, forever, forever);
    }

    @Override
    public Optional<ECKey> find(String kid) throws KeysUnavailableException {
        Snapshot current = snapshot;
        if (current == null || clock.instant().isAfter(current.fetchedAt().plus(cacheTtl))) {
            current = refresh(current);
        }
        if (current == null) {
            throw new KeysUnavailableException("the gateway JWKS has never been fetched", null);
        }
        if (clock.instant().isAfter(current.fetchedAt().plus(maxStale))) {
            throw new KeysUnavailableException("the cached gateway JWKS is older than the allowed staleness", null);
        }
        ECKey key = current.keys().get(kid);
        if (key == null) {
            Snapshot refreshed = refresh(current);
            key = refreshed == null ? null : refreshed.keys().get(kid);
        }
        return Optional.ofNullable(key);
    }

    /**
     * One fetch at a time. While it runs, other requests that already have a copy go on with it (still bounded
     * by {@code maxStale} in {@link #find}) instead of queueing behind a slow gateway; only a request with no
     * copy at all waits.
     */
    private Snapshot refresh(Snapshot seen) {
        if (seen != null) {
            if (!refreshing.tryLock()) {
                return seen;
            }
        } else {
            refreshing.lock();
        }
        try {
            if (snapshot != seen) {
                return snapshot;
            }
            Instant now = clock.instant();
            if (lastAttempt != null && now.isBefore(lastAttempt.plus(refreshMinInterval))) {
                return snapshot;
            }
            lastAttempt = now;
            try {
                snapshot = new Snapshot(signingKeys(fetcher.fetch()), now);
                LOG.info("event=gateway_jwks_fetched kids={}", snapshot.keys().keySet());
            } catch (Exception ex) {
                // The exception text may quote a response body; the class name says enough to diagnose.
                LOG.warn("event=gateway_jwks_fetch_failed error={} cachedAgeSeconds={}", ex.getClass().getName(),
                        snapshot == null ? "none" : Duration.between(snapshot.fetchedAt(), now).toSeconds());
            }
            return snapshot;
        } finally {
            refreshing.unlock();
        }
    }

    private static Map<String, ECKey> signingKeys(String jwksJson) throws java.text.ParseException {
        Map<String, ECKey> keys = new HashMap<>();
        Set<String> duplicated = new HashSet<>();
        for (JWK jwk : JWKSet.parse(jwksJson).getKeys()) {
            if (!(jwk instanceof ECKey ec) || !Curve.P_256.equals(ec.getCurve()) || ec.getKeyID() == null) {
                continue;
            }
            if (ec.getKeyUse() != null && !KeyUse.SIGNATURE.equals(ec.getKeyUse())) {
                continue;
            }
            if (ec.getAlgorithm() != null && !JWSAlgorithm.ES256.equals(ec.getAlgorithm())) {
                continue;
            }
            if (keys.putIfAbsent(ec.getKeyID(), ec.toPublicJWK()) != null) {
                duplicated.add(ec.getKeyID());
            }
        }
        // Two keys under one kid: no way to tell which one the gateway meant, so neither is trusted.
        duplicated.forEach(keys::remove);
        return Map.copyOf(keys);
    }
}
