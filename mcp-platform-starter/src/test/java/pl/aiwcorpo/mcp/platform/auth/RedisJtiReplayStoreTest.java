package pl.aiwcorpo.mcp.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Redis jti store against Redis with the ACL of the dev stack ({@code redis/start.sh}): user
 * {@code mcp-jira}, keys {@code jti:mcp-jira:*} only, an explicit command list without scripts or SCAN.
 */
@Testcontainers
class RedisJtiReplayStoreTest {

    private static final String PASSWORD = "jti-store-test-password";

    private static final String COMMANDS = "-@all +get +set +setnx +getdel +getex +mget +del +unlink +exists +expire "
            + "+pexpire +ttl +pttl +incr +incrby +decr +hset +hget +hdel +hgetall +hexists +hincrby +multi +exec +discard "
            + "+ping +auth +hello +client|setname +client|setinfo +reset +quit";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.4-alpine")
            .withCopyToContainer(Transferable.of("""
                    user default off resetkeys resetchannels -@all
                    user mcp-jira on >%s resetkeys ~jti:mcp-jira:* resetchannels %s
                    user other on >%s resetkeys ~jti:mcp-other:* resetchannels %s
                    """.formatted(PASSWORD, COMMANDS, PASSWORD, COMMANDS)), "/etc/redis/users.acl")
            .withCommand("redis-server", "--aclfile", "/etc/redis/users.acl", "--appendonly", "no")
            .withExposedPorts(6379);

    private static LettuceConnectionFactory factory;

    @BeforeAll
    static void connect() {
        factory = factory("mcp-jira");
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    private static LettuceConnectionFactory factory(String user) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        config.setUsername(user);
        config.setPassword(PASSWORD);
        LettuceConnectionFactory connections = new LettuceConnectionFactory(config);
        connections.afterPropertiesSet();
        connections.start();
        return connections;
    }

    private RedisJtiReplayStore store(LettuceConnectionFactory connections, String serverId) {
        return new RedisJtiReplayStore(new StringRedisTemplate(connections), serverId, Clock.systemUTC());
    }

    @Test
    void aJtiIsAcceptedOnceUnderTheServersAcl() throws Exception {
        RedisJtiReplayStore store = store(factory, "mcp-jira");
        Instant until = Instant.now().plusSeconds(90);

        assertThat(store.firstUse("0192d1b2-7c3a-7f00-9a11-00000000a001", until)).isTrue();
        assertThat(store.firstUse("0192d1b2-7c3a-7f00-9a11-00000000a001", until)).isFalse();
        assertThat(store.firstUse("0192d1b2-7c3a-7f00-9a11-00000000a002", until)).isTrue();
    }

    @Test
    void theKeyExpiresWithTheToken() {
        RedisJtiReplayStore store = store(factory, "mcp-jira");
        StringRedisTemplate admin = new StringRedisTemplate(factory);

        assertThat(callQuietly(() -> store.firstUse("ttl-check", Instant.now().plusSeconds(90)))).isTrue();

        Long ttl = admin.getExpire("jti:mcp-jira:ttl-check");
        assertThat(ttl).isBetween(80L, 91L);
    }

    @Test
    void concurrentCopiesOfOneTokenPassExactlyOnce() throws Exception {
        RedisJtiReplayStore store = store(factory, "mcp-jira");
        Instant until = Instant.now().plusSeconds(90);
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                results.add(pool.submit(() -> store.firstUse("raced-jti", until)));
            }
            int accepted = 0;
            for (Future<Boolean> result : results) {
                accepted += result.get() ? 1 : 0;
            }
            assertThat(accepted).isEqualTo(1);
        }
    }

    @Test
    void aKeyOutsideTheServersPrefixFailsClosed() {
        // A server configured with another server id cannot write there: the ACL refuses, the store reports
        // unavailable and the request is refused rather than accepted without a replay check.
        RedisJtiReplayStore misconfigured = store(factory, "mcp-gitlab");

        assertThatThrownBy(() -> misconfigured.firstUse("x", Instant.now().plusSeconds(60)))
                .isInstanceOf(JtiReplayStore.StoreUnavailableException.class);
    }

    @Test
    void redisDownFailsClosed() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration("127.0.0.1", 1);
        LettuceConnectionFactory nowhere = new LettuceConnectionFactory(config);
        nowhere.afterPropertiesSet();
        try {
            RedisJtiReplayStore store = new RedisJtiReplayStore(new StringRedisTemplate(nowhere), "mcp-jira",
                    Clock.systemUTC());
            assertThatThrownBy(() -> store.firstUse("x", Instant.now().plusSeconds(60)))
                    .isInstanceOf(JtiReplayStore.StoreUnavailableException.class);
        } finally {
            nowhere.destroy();
        }
    }

    private static <T> T callQuietly(Callable<T> call) {
        try {
            return call.call();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
