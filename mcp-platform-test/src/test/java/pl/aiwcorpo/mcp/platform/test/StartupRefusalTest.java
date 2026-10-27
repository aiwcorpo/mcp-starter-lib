package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/** Development relaxations outside the {@code dev} profile stop the server instead of weakening it. */
class StartupRefusalTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");

    @AfterAll
    static void stop() {
        GATEWAY.close();
    }

    private static void start(Map<String, Object> overrides, String... profiles) {
        Map<String, Object> properties = new HashMap<>(GATEWAY.serverProperties());
        properties.put("spring.ai.mcp.server.name", "startup-refusal-test");
        properties.putAll(overrides);
        new SpringApplicationBuilder(ProbeServer.class).web(WebApplicationType.SERVLET).properties(properties)
                .properties("server.port=0").profiles(profiles).run().close();
    }

    @Test
    void testKeysWithoutTheDevProfile() {
        assertThatThrownBy(() -> start(Map.of(), "prod")).rootCause()
                .hasMessageContaining("allow-test-keys=true is allowed only with the 'dev' profile")
                .hasMessageContaining("replay-store=memory").hasMessageContaining("mtls.required=false");
    }

    @Test
    void devPatWithoutTheDevProfile() {
        assertThatThrownBy(() -> start(Map.of("aiwcorpo.mcp.platform.auth-mode", "dev-pat",
                "aiwcorpo.mcp.platform.internal-jwt.allow-test-keys", "false",
                "aiwcorpo.mcp.platform.internal-jwt.replay-store", "redis",
                "aiwcorpo.mcp.platform.mtls.required", "true")))
                .rootCause().hasMessageContaining("auth-mode=dev-pat is allowed only with the 'dev' profile");
    }

    @Test
    void aServerWithoutServerIdOrJwks() {
        assertThatThrownBy(() -> start(Map.of("aiwcorpo.mcp.platform.server-id", "",
                "aiwcorpo.mcp.platform.internal-jwt.jwks-uri", ""), "dev"))
                .rootCause().hasMessageContaining("server-id").hasMessageContaining("jwks-uri");
    }
}
