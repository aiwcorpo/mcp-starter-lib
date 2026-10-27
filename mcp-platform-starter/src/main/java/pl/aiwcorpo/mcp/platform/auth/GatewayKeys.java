package pl.aiwcorpo.mcp.platform.auth;

import com.nimbusds.jose.jwk.ECKey;
import java.util.Optional;

/** The gateway's signing keys (ES256, P-256) by {@code kid}. */
public interface GatewayKeys {

    /**
     * @return the key with this {@code kid}, empty when the gateway does not publish it
     * @throws KeysUnavailableException when the keys cannot be determined (JWKS unreachable, nothing cached)
     */
    Optional<ECKey> find(String kid) throws KeysUnavailableException;

    class KeysUnavailableException extends Exception {

        public KeysUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
