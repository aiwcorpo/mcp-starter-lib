package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /.well-known/mcp-policy-data} and the gate of {@code tools/call} (C14) over the real transport: the first push when the server
 * starts, the replacement rule, the purpose of the tokens, a body that is not the document, and {@code 409 policy_data_stale} before anything else.
 */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PolicyDataPushTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String POLICY_DATA = "/.well-known/mcp-policy-data";

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
    }

    @AfterAll
    static void stop() {
        GATEWAY.close();
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private URI origin() {
        return URI.create("http://127.0.0.1:" + port);
    }

    private HttpResponse<String> post(String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin() + path)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The revision the server holds, as a push of the current document says. */
    private long held() throws Exception {
        return GATEWAY.policyData().pushTo(origin()).revision();
    }

    private static String reason(HttpResponse<String> response) {
        return JSON.readTree(response.body()).path("reason").asString("");
    }

    @Test
    @Order(1)
    void theFirstPushWentOutWhenTheWebServerStartedAndTheServerEvaluates() throws Exception {
        assertThat(GATEWAY.policyData().pushes()).as("pushed at start, by the listener of this library").isGreaterThanOrEqualTo(1);
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);

        HttpResponse<String> call = raw.post(RawMcp.toolCall("probe_caller"), GATEWAY.user().tool("probe_caller").mint().headers(), session);

        assertThat(call.statusCode()).isEqualTo(200);
        assertThat(call.body()).contains("jkowalski|probe_caller");
    }

    @Test
    @Order(2)
    void aPushReplacesOnlyAStrictlyNewerRevisionAndTheAnswerSaysWhatIsKept() throws Exception {
        // The test gateway starts at revision 1: raise it, so that there is an older one to push.
        GATEWAY.policyData().nextRevision(1000).allowedClients("claude-code", "claude-ai", "local-gateway");
        PolicyDataEndpoint.Ack same = GATEWAY.policyData().pushTo(origin());
        assertThat(same).extracting(PolicyDataEndpoint.Ack::status, PolicyDataEndpoint.Ack::accepted, PolicyDataEndpoint.Ack::gate)
                .containsExactly(200, false, "ok");
        long held = same.revision();

        GATEWAY.policyData().pushing(false);
        try {
            GATEWAY.policyData().nextRevision(held - 1).allowedClients("claude-ai");
            PolicyDataEndpoint.Ack older = GATEWAY.policyData().pushTo(origin());
            assertThat(older).extracting(PolicyDataEndpoint.Ack::status, PolicyDataEndpoint.Ack::accepted, PolicyDataEndpoint.Ack::revision)
                    .containsExactly(200, false, held);

            GATEWAY.policyData().nextRevision(held + 1).allowedClients("claude-code", "claude-ai", "local-gateway");
            PolicyDataEndpoint.Ack newer = GATEWAY.policyData().pushTo(origin());
            assertThat(newer).extracting(PolicyDataEndpoint.Ack::status, PolicyDataEndpoint.Ack::accepted, PolicyDataEndpoint.Ack::revision)
                    .containsExactly(200, true, held + 1);
        } finally {
            GATEWAY.policyData().pushing(true);
        }
    }

    @Test
    @Order(3)
    void aCallWithAPrvNewerThanTheHeldRevisionIsAnswered409BeforeAnythingElseAndTheGatewayPushesAndRepeats() throws Exception {
        long held = held();
        long required = held + 100;
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);

        // No credential header and a session of another call: the 409 comes first, ahead of every other refusal.
        HttpResponse<String> stale = raw.post(RawMcp.toolCall("probe_credential"),
                GATEWAY.user().tool("probe_credential").policyRevision(required).mint().headers(), session);

        assertThat(stale.statusCode()).isEqualTo(409);
        assertThat(stale.headers().firstValue("Content-Type")).hasValueSatisfying(type -> assertThat(type).contains("application/json"));
        JsonNode body = JSON.readTree(stale.body());
        assertThat(body.get("error").asString()).isEqualTo("policy_data_stale");
        assertThat(body.get("heldRevision").asLong()).isEqualTo(held);
        assertThat(body.get("requiredRevision").asLong()).isEqualTo(required);
        assertThat(body.size()).isEqualTo(3);

        GATEWAY.policyData().nextRevision(required).allowedClients("claude-code", "claude-ai", "local-gateway");
        HttpResponse<String> repeated = raw.post(RawMcp.toolCall("probe_caller"),
                GATEWAY.user().tool("probe_caller").policyRevision(required).mint().headers(), session);

        assertThat(repeated.statusCode()).isEqualTo(200);
        assertThat(repeated.body()).contains("jkowalski|probe_caller");
    }

    @Test
    @Order(4)
    void aTokenOfAnotherPurposeIsRefusedAtEveryEndpointAndTheJtiIsOneTime() throws Exception {
        String data = GATEWAY.policyData().json();
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);

        HttpResponse<String> callTokenAtPush = post(POLICY_DATA, "{}", GATEWAY.service().mint().headers());
        assertThat(callTokenAtPush.statusCode()).isEqualTo(401);
        assertThat(reason(callTokenAtPush)).isEqualTo("wrong_purpose");
        assertThat(reason(post(POLICY_DATA, "{}", GATEWAY.user().tool("probe_caller").mint().headers()))).isEqualTo("wrong_purpose");
        assertThat(reason(post(POLICY_DATA, "{}", GATEWAY.manifestToken().mint().headers()))).isEqualTo("wrong_purpose");

        HttpResponse<String> pushTokenAtCall = raw.post(RawMcp.toolCall("probe_caller"),
                GATEWAY.service().use(TestGateway.USE_POLICY_DATA).mint().headers(), session);
        assertThat(pushTokenAtCall.statusCode()).isEqualTo(401);
        assertThat(reason(pushTokenAtCall)).as("the MCP endpoint keeps the reason out of the body").isEmpty();
        HttpResponse<String> manifestTokenAtInitialize = raw.post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}",
                GATEWAY.manifestToken().mint().headers(), session);
        assertThat(manifestTokenAtInitialize.statusCode()).isEqualTo(401);

        TestGateway.Minted once = GATEWAY.service().use(TestGateway.USE_POLICY_DATA).mint();
        assertThat(post(POLICY_DATA, data, once.headers()).statusCode()).isEqualTo(200);
        HttpResponse<String> replay = post(POLICY_DATA, data, once.headers());
        assertThat(replay.statusCode()).isEqualTo(401);
        assertThat(reason(replay)).isEqualTo("replayed_jti");
    }

    @Test
    @Order(5)
    void aBodyThatIsNotThePolicyDataIsRefusedAndChangesNothing() throws Exception {
        long held = held();
        for (String bad : new String[] {"{}", "not json", "[]", "{\"revision\":0,\"scopeMapping\":[],\"allowedClients\":[],\"overrides\":[]}",
                "{\"revision\":" + (held + 50) + ",\"scopeMapping\":{},\"allowedClients\":[],\"overrides\":[]}"}) {
            HttpResponse<String> response = post(POLICY_DATA, bad, GATEWAY.service().use(TestGateway.USE_POLICY_DATA).mint().headers());

            assertThat(response.statusCode()).as(bad).isEqualTo(400);
            assertThat(JSON.readTree(response.body()).get("error").asString()).as(bad).isEqualTo("invalid_request");
        }
        assertThat(held()).as("nothing was replaced").isEqualTo(held);

        String huge = "{\"pad\":\"" + "x".repeat(4 * 1024 * 1024) + "\"}";
        HttpResponse<String> tooLarge = post(POLICY_DATA, huge, GATEWAY.service().use(TestGateway.USE_POLICY_DATA).mint().headers());
        assertThat(tooLarge.statusCode()).isEqualTo(413);
        assertThat(JSON.readTree(tooLarge.body()).get("error").asString()).isEqualTo("invalid_request");
    }

    @Test
    @Order(6)
    void dataThatMakesTheGateRefuseIsTakenAndTheAnswerSaysWhy() throws Exception {
        GATEWAY.policyData().pushing(false);
        try {
            GATEWAY.policyData().unapproved();
            PolicyDataEndpoint.Ack unapproved = GATEWAY.policyData().pushTo(origin());
            assertThat(unapproved).extracting(PolicyDataEndpoint.Ack::status, PolicyDataEndpoint.Ack::accepted, PolicyDataEndpoint.Ack::gate)
                    .containsExactly(200, true, "policies_not_approved");

            RawMcp raw = new RawMcp(port);
            String session = raw.openSession(GATEWAY);
            HttpResponse<String> call = raw.post(RawMcp.toolCall("probe_caller"), GATEWAY.user().tool("probe_caller").mint().headers(), session);
            assertThat(call.body()).as("the answer is an MCP error, not a 409: the gateway does not push for it")
                    .contains("upstream_unavailable");
            assertThat(call.statusCode()).isNotEqualTo(409);

            GATEWAY.policyData().approved();
            assertThat(GATEWAY.policyData().pushTo(origin()).gate()).isEqualTo("ok");
        } finally {
            GATEWAY.policyData().pushing(true);
        }
    }
}
