package pl.aiwcorpo.mcp.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The verifier accepts a token through the whole second {@code exp + skew}; the jti must be kept at least as long, or a store that drops
 * an entry when its retention ends (Redis with its TTL) lets a used token through once more.
 */
class JtiRetentionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Forgets an entry as soon as the clock is not before the retention end it was given, as an expiring Redis key does. */
    private static final class ExpiringStore implements JtiReplayStore {

        private final Map<String, Instant> kept = new HashMap<>();
        private final Clock clock;

        ExpiringStore(Clock clock) {
            this.clock = clock;
        }

        @Override
        public boolean firstUse(String jti, Instant validUntil) {
            Instant until = kept.get(jti);
            if (until != null && clock.instant().isBefore(until)) {
                return false;
            }
            kept.put(jti, validUntil);
            return true;
        }
    }

    private static final class MovingClock extends Clock {

        Instant now;

        MovingClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void aUsedTokenIsNotAcceptedAgainInItsLastValidSecond() throws Exception {
        JsonNode vector = JSON.readTree(InternalJwtFilterTest.read("jwt/valid.json"));
        String token = vector.get("token").asString();
        RequestBinding binding = InternalJwtVectorsTest.bindingFor(vector.get("request"));
        long exp = JSON.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8))
                .get("exp").asLong();
        MovingClock clock = new MovingClock(Instant.parse(vector.get("verifier").get("now").asString()));
        InternalJwtVerifier verifier = new InternalJwtVerifier(new InternalJwtVerifier.Settings(
                "https://mcp-gateway.internal", "mcp-jira", Duration.ofSeconds(30), Duration.ofSeconds(60), true),
                JwksGatewayKeys.of(InternalJwtFilterTest.read("keys/test-gateway-jwks.json")), new ExpiringStore(clock), clock);

        assertThat(verifier.verify(token, binding)).isNotNull();

        clock.now = Instant.ofEpochSecond(exp + 30).plusMillis(500);
        assertThatThrownBy(() -> verifier.verify(token, binding))
                .isInstanceOfSatisfying(InternalJwtRejectedException.class,
                        refused -> assertThat(refused.reason()).isEqualTo(InternalJwtRejection.REPLAYED_JTI));
    }
}
