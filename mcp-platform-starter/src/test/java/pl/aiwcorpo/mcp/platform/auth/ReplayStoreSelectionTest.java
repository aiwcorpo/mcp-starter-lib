package pl.aiwcorpo.mcp.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;

/** The one-time jti store: the memory of the process unless Redis is asked for (CC-249); bounded, and entries end with the token. */
class ReplayStoreSelectionTest {

    private static McpPlatformProperties properties(McpPlatformProperties.ReplayStore store) {
        McpPlatformProperties props = new McpPlatformProperties();
        props.getInternalJwt().setReplayStore(store);
        return props;
    }

    @Test
    void withoutAChoiceRedisIsUsedOnlyWhenAHostIsSet() {
        assertThat(PlatformAuthAutoConfiguration.redisReplayStore(properties(null), new MockEnvironment())).isFalse();
        assertThat(PlatformAuthAutoConfiguration.redisReplayStore(properties(null),
                new MockEnvironment().withProperty("spring.data.redis.host", ""))).isFalse();
        assertThat(PlatformAuthAutoConfiguration.redisReplayStore(properties(null),
                new MockEnvironment().withProperty("spring.data.redis.host", "redis.corp.test"))).isTrue();
    }

    @Test
    void anExplicitChoiceWins() {
        assertThat(PlatformAuthAutoConfiguration.redisReplayStore(properties(McpPlatformProperties.ReplayStore.MEMORY),
                new MockEnvironment().withProperty("spring.data.redis.host", "redis.corp.test"))).isFalse();
        assertThat(PlatformAuthAutoConfiguration.redisReplayStore(properties(McpPlatformProperties.ReplayStore.REDIS), new MockEnvironment())).isTrue();
    }

    @Test
    void theMemoryStoreRefusesASecondUseUntilTheTokenHasEndedAndIsBounded() throws Exception {
        Instant start = Instant.parse("2026-11-08T12:00:00Z");
        MutableClock clock = new MutableClock(start);
        InMemoryJtiReplayStore store = new InMemoryJtiReplayStore(clock);

        assertThat(store.firstUse("jti-1", start.plusSeconds(90))).isTrue();
        assertThat(store.firstUse("jti-1", start.plusSeconds(90))).isFalse();

        for (int i = 0; i < 100_000 - 1; i++) {
            store.firstUse("fill-" + i, start.plusSeconds(90));
        }
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.firstUse("one-more", start.plusSeconds(90)))
                .as("full of live entries: refuse (fail-closed) instead of growing")
                .isInstanceOf(JtiReplayStore.StoreUnavailableException.class);

        clock.set(start.plus(Duration.ofSeconds(120)));
        assertThat(store.firstUse("one-more", start.plusSeconds(210))).as("the ended entries are dropped").isTrue();
    }

    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant value) {
            now = value;
        }

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
            return now;
        }
    }
}
