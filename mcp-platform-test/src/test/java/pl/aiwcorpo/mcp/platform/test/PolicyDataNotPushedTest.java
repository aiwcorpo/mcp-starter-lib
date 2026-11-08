package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A server the gateway has not pushed to (it just started, or it missed the push) holds no policy data and refuses {@code tools/call}
 * as {@code 409 policy_data_stale} with {@code heldRevision: null}, while the rest of its interface keeps working; after the push it serves.
 */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class PolicyDataNotPushedTest {

    @org.junit.jupiter.api.BeforeAll
    static void freshState() throws Exception {
        org.springframework.util.FileSystemUtils.deleteRecursively(java.nio.file.Path.of("target", "policy-data-not-pushed-state"));
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        // Without the property that names the gateway, nothing attaches the server when it starts.
        GATEWAY.serverProperties().forEach((name, value) -> {
            if (!name.endsWith("test.gateway")) {
                registry.add(name, () -> value);
            }
        });
    }

    @AfterAll
    static void stop() {
        GATEWAY.close();
    }

    @LocalServerPort
    int port;

    @Test
    void aServerWithoutDataRefusesCallsAs409WithNothingHeldUntilTheGatewayPushes() throws Exception {
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);
        assertThat(GATEWAY.policyData().pushes()).isZero();

        HttpResponse<String> stale = raw.post(RawMcp.toolCall("probe_caller"), GATEWAY.user().tool("probe_caller").mint().headers(), session);

        assertThat(stale.statusCode()).isEqualTo(409);
        JsonNode body = JSON.readTree(stale.body());
        assertThat(body.get("error").asString()).isEqualTo("policy_data_stale");
        assertThat(body.get("heldRevision").isNull()).isTrue();
        assertThat(body.get("requiredRevision").asLong()).isEqualTo(TestGateway.DEFAULT_POLICY_REVISION);

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest.Builder manifest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/.well-known/mcp-manifest")).GET();
        assertThat(http.send(manifest.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).as("the manifest needs no policy data").isEqualTo(200);
        assertThat(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);

        PolicyDataEndpoint.Ack ack = GATEWAY.policyData().attach(URI.create("http://127.0.0.1:" + port));
        assertThat(ack).extracting(PolicyDataEndpoint.Ack::status, PolicyDataEndpoint.Ack::accepted, PolicyDataEndpoint.Ack::gate)
                .containsExactly(200, true, "ok");

        HttpResponse<String> repeated = raw.post(RawMcp.toolCall("probe_caller"), GATEWAY.user().tool("probe_caller").mint().headers(), session);
        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(repeated.body()).contains("jkowalski|probe_caller");
        assertThat(http.send(manifest.build(), HttpResponse.BodyHandlers.ofString()).statusCode()).as("and still needs none after the push").isEqualTo(200);
    }
}
