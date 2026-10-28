package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/**
 * A server's own tests with {@link TestGateway} need the {@code dev} profile and run in CI on Kubernetes pods and
 * under systemd runners; the deployment check must not apply to a Spring test context.
 */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.name=deployment-context-test", "INVOCATION_ID=7f3a9c1e5b2d4e8f",
                "KUBERNETES_SERVICE_HOST=10.96.0.1", "KUBERNETES_SERVICE_PORT=443"})
@ActiveProfiles("dev")
@DisplayName("dev profile in a test context on a deployment host")
class DeploymentContextTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    @AfterAll
    static void stop() {
        GATEWAY.close();
    }

    @Autowired
    Environment environment;

    @Test
    void theContextStartsAlthoughKubernetesAndSystemdAreVisible() {
        assertThat(environment.getProperty("INVOCATION_ID")).isNotBlank();
        assertThat(environment.getProperty("KUBERNETES_SERVICE_HOST")).isNotBlank();
        assertThat(environment.getActiveProfiles()).containsExactly("dev");
    }
}
