package pl.aiwcorpo.mcp.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class JwksGatewayKeysTest {

    private final String jwks = InternalJwtFilterTest.read("keys/test-gateway-jwks.json");
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-26T12:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @Test
    void unknownKidsDoNotTurnEveryRequestIntoAFetch() throws Exception {
        AtomicInteger fetches = new AtomicInteger();
        JwksGatewayKeys keys = new JwksGatewayKeys(() -> {
            fetches.incrementAndGet();
            return jwks;
        }, clock, Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofSeconds(30));

        assertThat(keys.find("test-gw-2026-10-a")).isPresent();
        for (int i = 0; i < 50; i++) {
            assertThat(keys.find("random-" + i)).isEmpty();
        }
        assertThat(fetches.get()).as("within the minimum interval after the first fetch").isEqualTo(1);

        now.set(now.get().plusSeconds(31));
        for (int i = 0; i < 50; i++) {
            assertThat(keys.find("random-" + i)).isEmpty();
        }
        assertThat(fetches.get()).as("one fetch per interval, for a possibly rotated key").isEqualTo(2);
    }

    @Test
    void aStaleCopyIsUsedOnlyUntilMaxStaleThenRequestsFail() throws Exception {
        AtomicReference<Boolean> up = new AtomicReference<>(true);
        JwksGatewayKeys keys = new JwksGatewayKeys(() -> {
            if (!up.get()) {
                throw new IOException("gateway down");
            }
            return jwks;
        }, clock, Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofSeconds(30));
        assertThat(keys.find("test-gw-2026-10-b")).isPresent();

        up.set(false);
        now.set(now.get().plus(Duration.ofMinutes(30)));
        assertThat(keys.find("test-gw-2026-10-b")).as("within max-stale").isPresent();

        now.set(now.get().plus(Duration.ofMinutes(31)));
        assertThatThrownBy(() -> keys.find("test-gw-2026-10-b")).isInstanceOf(GatewayKeys.KeysUnavailableException.class);
    }

    @Test
    void duplicateKidsAndForeignKeysAreIgnored() throws Exception {
        String a = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256).keyID("dup")
                .generate().toPublicJWK().toJSONString();
        String b = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256).keyID("dup")
                .generate().toPublicJWK().toJSONString();
        String rsa = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("rsa").generate().toPublicJWK().toJSONString();
        String p384 = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_384).keyID("p384")
                .generate().toPublicJWK().toJSONString();
        JwksGatewayKeys keys = JwksGatewayKeys.of("{\"keys\":[" + a + "," + b + "," + rsa + "," + p384 + "]}");

        assertThat(keys.find("dup")).isEmpty();
        assertThat(keys.find("rsa")).isEmpty();
        assertThat(keys.find("p384")).isEmpty();
    }

    @Test
    void whileOneFetchIsSlowOthersKeepUsingTheirCopy() throws Exception {
        java.util.concurrent.CountDownLatch inFetch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger fetches = new AtomicInteger();
        JwksGatewayKeys keys = new JwksGatewayKeys(() -> {
            if (fetches.incrementAndGet() > 1) {
                inFetch.countDown();
                release.await();
            }
            return jwks;
        }, clock, Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofSeconds(30));
        assertThat(keys.find("test-gw-2026-10-a")).isPresent();

        now.set(now.get().plus(Duration.ofMinutes(11)));
        Thread slow = Thread.ofVirtual().start(() -> {
            try {
                keys.find("test-gw-2026-10-a");
            } catch (GatewayKeys.KeysUnavailableException ex) {
                throw new IllegalStateException(ex);
            }
        });
        inFetch.await();
        long start = System.nanoTime();
        assertThat(keys.find("test-gw-2026-10-b")).isPresent();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        release.countDown();
        slow.join();
    }
}
