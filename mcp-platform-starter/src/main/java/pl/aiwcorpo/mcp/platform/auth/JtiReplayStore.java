package pl.aiwcorpo.mcp.platform.auth;

import java.time.Instant;

/** Remembers the {@code jti} of every accepted internal JWT for as long as the token could still be valid. */
public interface JtiReplayStore {

    /**
     * Records {@code jti} until {@code validUntil} and says whether this was its first use. Atomic: of two
     * concurrent calls with the same {@code jti}, exactly one gets {@code true}.
     *
     * @throws StoreUnavailableException when the store cannot answer; the request is then refused
     */
    boolean firstUse(String jti, Instant validUntil) throws StoreUnavailableException;

    class StoreUnavailableException extends Exception {

        public StoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
