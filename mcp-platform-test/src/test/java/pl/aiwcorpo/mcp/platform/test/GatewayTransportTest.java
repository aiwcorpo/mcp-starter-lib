package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/**
 * The starter over the real transport (Tomcat + Spring AI streamable HTTP): who gets in, what a tool sees.
 */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.type=SYNC",
                "spring.ai.mcp.server.name=platform-transport-test",
                "spring.ai.mcp.server.streamable-http.mcp-endpoint=" + RawMcp.ENDPOINT})
@ActiveProfiles("dev")
@DisplayName("the gateway model over the real transport")
class GatewayTransportTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");
    private static final String PAT = "jira-pat-of-jkowalski-0000";

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

    private McpSyncClient client(String credential) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port).endpoint(RawMcp.ENDPOINT).openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(user -> user.username("jkowalski"), credential))
                .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build();
        client.initialize();
        return client;
    }

    static String text(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        result.content().forEach(c -> {
            if (c instanceof McpSchema.TextContent t) {
                text.append(t.text());
            }
        });
        return text.toString();
    }

    private static McpSchema.CallToolResult call(McpSyncClient client, String tool) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(Map.of()).build());
    }

    @Test
    @DisplayName("the gateway's credential and the verified caller reach the tool")
    void credentialAndCallerReachTheTool() {
        try (McpSyncClient client = client("Bearer " + PAT)) {
            assertThat(text(call(client, "probe_credential"))).isEqualTo("\"user-secret Bearer " + PAT + "\"");
            assertThat(text(call(client, "probe_caller"))).startsWith("\"jkowalski|probe_caller|");
            assertThat(client.listTools().tools()).extracting(McpSchema.Tool::name).contains("probe_credential");
        }
    }

    @Test
    @DisplayName("asGateway with a credential type: the tool sees that type")
    void theCredentialTypeSetByTheTestGatewayReachesTheTool() {
        for (BackendCredential.Type type : BackendCredential.Type.values()) {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                    .builder("http://127.0.0.1:" + port).endpoint(RawMcp.ENDPOINT).openConnectionOnStartup(false)
                    .httpRequestCustomizer(GATEWAY.asGateway(user -> { }, "Bearer " + PAT, type)).build();
            try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build()) {
                client.initialize();
                if (type == BackendCredential.Type.USER_SECRET || type == BackendCredential.Type.USER_OAUTH) {
                    assertThat(text(call(client, "probe_credential"))).isEqualTo("\"" + type.wireName() + " Bearer " + PAT + "\"");
                } else {
                    // TestGateway accepts user-secret and user-oauth; another type is outside the server's set (CC-166).
                    assertThat(text(call(client, "probe_credential"))).contains("\"error\":\"internal_error\"").doesNotContain(PAT);
                }
            }
        }
    }

    @Test
    @DisplayName("a credential without a usable type header is refused as internal_error and audited as credential_type_mismatch (CC-166)")
    void aCredentialWithoutAUsableTypeIsRefused() throws Exception {
        ch.qos.logback.classic.Logger audit =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("pl.aiwcorpo.mcp.audit");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> lines =
                new ch.qos.logback.core.read.ListAppender<>();
        lines.start();
        audit.addAppender(lines);
        try {
            RawMcp raw = new RawMcp(port);
            String session = raw.openSession(GATEWAY);
            for (String type : new String[] {null, "api-key", "User-OAuth", "service-account"}) {
                Map<String, String> headers = new java.util.HashMap<>(GATEWAY.user().tool("probe_credential").mint().headers());
                headers.put("X-MCP-Credential", "Bearer " + PAT);
                if (type != null) {
                    headers.put("X-MCP-Credential-Type", type);
                }
                HttpResponse<String> response = raw.post(RawMcp.toolCall("probe_credential"), headers, session);

                assertThat(response.statusCode()).as(String.valueOf(type)).isEqualTo(200);
                assertThat(response.body()).as(String.valueOf(type)).contains("internal_error").doesNotContain(PAT);
            }
            assertThat(CapturedLogs.snapshot(lines).stream().filter(line -> line.getFormattedMessage()
                    .contains("\"reason\":\"credential_type_mismatch\"")).count()).isEqualTo(4);
            assertThat(CapturedLogs.snapshot(lines)).noneSatisfy(line -> assertThat(line.getFormattedMessage()).contains(PAT));
        } finally {
            audit.detachAppender(lines);
        }
    }

    @Test
    @DisplayName("no credential: credential_required with the portal link")
    void noCredentialMeansCredentialRequired() {
        try (McpSyncClient client = client(null)) {
            McpSchema.CallToolResult result = call(client, "probe_credential");
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).isEqualTo("{\"error\":\"credential_required\",\"message\":\"Connect your jira account "
                    + "in the portal, then try again.\",\"system\":\"jira\",\"connectUrl\":"
                    + "\"http://localhost:18090/connections?system=jira\"}");
        }
    }

    @Test
    @DisplayName("without a JWT: 401, never anonymous, and an authentication event in the audit")
    void withoutJwt401() throws Exception {
        ch.qos.logback.classic.Logger audit =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("pl.aiwcorpo.mcp.audit");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> lines =
                new ch.qos.logback.core.read.ListAppender<>();
        lines.start();
        audit.addAppender(lines);
        RawMcp raw = new RawMcp(port);
        HttpResponse<String> response;
        try {
            response = raw.post(RawMcp.toolCall("probe_caller"), Map.of(), null);
            assertThat(CapturedLogs.snapshot(lines)).anySatisfy(line -> assertThat(line.getFormattedMessage())
                    .contains("\"event\":\"authentication\"", "\"reason\":\"missing_token\"",
                            "\"outcome\":\"denied\"", "\"errorCode\":\"unauthenticated\"")
                    .doesNotContain("\"tool\""));
        } finally {
            audit.detachAppender(lines);
        }

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("\"error\":\"unauthenticated\"");
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(raw.post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}", Map.of(), null).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("the service variant on tools/call: 401; on tools/list: accepted")
    void serviceVariantOnlyForProtocolRequests() throws Exception {
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);

        HttpResponse<String> call = raw.post(RawMcp.toolCall("probe_caller"), GATEWAY.service().mint().headers(), session);
        assertThat(call.statusCode()).isEqualTo(401);
        assertThat(call.body()).doesNotContain("jkowalski");

        HttpResponse<String> list = raw.post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}",
                GATEWAY.service().mint().headers(), session);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("probe_caller");
    }

    @Test
    @DisplayName("a token with the removed virtual server claims vs or vsc: 401 (invalid_claims), never a call")
    void removedVirtualServerClaims() throws Exception {
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);

        TestGateway.Minted withVs = GATEWAY.user().tool("probe_caller").extraClaim("vs", "team-a-review").mint();
        TestGateway.Minted withVsc = GATEWAY.user().tool("probe_caller").extraClaim("vsc", Map.of()).mint();
        TestGateway.Minted withBoth = GATEWAY.user().tool("probe_caller").extraClaim("vs", "abc-only")
                .extraClaim("vsc", Map.of("jiraProjects", List.of("ABC"))).mint();
        for (TestGateway.Minted token : List.of(withVs, withVsc, withBoth)) {
            assertThat(raw.post(RawMcp.toolCall("probe_caller"), token.headers(), session).statusCode()).isEqualTo(401);
        }
        assertThat(raw.post(RawMcp.toolCall("probe_caller"), GATEWAY.user().tool("probe_caller").mint().headers(), session).statusCode())
                .as("the same call without the claims").isEqualTo(200);
    }

    @Test
    @DisplayName("the service variant (system:mcp-gateway) with vs: 401 as well")
    void serviceVariantWithRemovedVirtualServerClaim() throws Exception {
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);

        HttpResponse<String> list = raw.post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}",
                GATEWAY.service().extraClaim("vs", "team-a-review").mint().headers(), session);
        assertThat(list.statusCode()).isEqualTo(401);
        assertThat(raw.post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}", GATEWAY.service().mint().headers(), session)
                .statusCode()).as("without the claim").isEqualTo(200);
    }

    @Test
    @DisplayName("a token is bound to one tool, one request id and one use")
    void tokenBinding() throws Exception {
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);

        TestGateway.Minted forOtherTool = GATEWAY.user().tool("probe_credential").mint();
        assertThat(raw.post(RawMcp.toolCall("probe_caller"), forOtherTool.headers(), session).statusCode()).isEqualTo(401);

        TestGateway.Minted good = GATEWAY.user().tool("probe_caller").mint();
        assertThat(raw.post(RawMcp.toolCall("probe_caller"), good.headers(), session).statusCode()).isEqualTo(200);
        assertThat(raw.post(RawMcp.toolCall("probe_caller"), good.headers(), session).statusCode())
                .as("replayed jti").isEqualTo(401);

        TestGateway.Minted otherRid = GATEWAY.user().tool("probe_caller").mint();
        Map<String, String> mismatched = Map.of("Authorization", otherRid.headers().get("Authorization"),
                "X-Request-Id", GATEWAY.user().mint().requestId());
        assertThat(raw.post(RawMcp.toolCall("probe_caller"), mismatched, session).statusCode()).isEqualTo(401);

        TestGateway.Minted tooLong = GATEWAY.user().tool("probe_caller").lifetime(Duration.ofSeconds(61)).mint();
        assertThat(raw.post(RawMcp.toolCall("probe_caller"), tooLong.headers(), session).statusCode()).isEqualTo(401);

        TestGateway.Minted otherServer = GATEWAY.user().tool("probe_caller").audience("mcp-gitlab").mint();
        assertThat(raw.post(RawMcp.toolCall("probe_caller"), otherServer.headers(), session).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("health stays reachable without a JWT")
    void healthIsExempt() throws Exception {
        assertThat(new RawMcp(port).rawStatus("GET", "/actuator/health", null)).isEqualTo(200);
    }

    /**
     * Spellings of the MCP endpoint (or of a path under an exempt one) that a container may normalize: none
     * reaches anything without a JWT. Sent byte for byte over a socket, so only Tomcat interprets them.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/mcp", "/mcp/", "/MCP", "//mcp", "/./mcp", "/mcp;x=1", "/%6dcp", "/%6Dcp",
            "/actuator/health/../../mcp", "/actuator/health/..;/..;/mcp", "/actuator/health/..;/mcp",
            "/actuator/health;x/../../mcp", "/actuator/health/%2e%2e/%2e%2e/mcp", "/actuator/health/%2E%2E/%2E%2E/mcp",
            "/actuator/health%2f..%2f..%2fmcp", "/actuator/health%2F..%2F..%2Fmcp", "/actuator/health/..%5c..%5cmcp",
            "/actuator/health/..\\..\\mcp", "//actuator/health/../../mcp", "/actuator//health/../../mcp",
            "/Actuator/Health/../../mcp", "/actuator/health/", "/actuator/health/x/../../../mcp",
            "/actuator/health/liveness/../../../mcp", "/actuator/health/liveness/..;/../mcp", "/actuator/health/%2e%2e;/mcp"})
    void noPathVariantGetsInWithoutJwt(String rawPath) throws Exception {
        int status = new RawMcp(port).rawStatus("POST", rawPath, RawMcp.toolCall("probe_caller"));

        assertThat(status).as("POST " + rawPath).isIn(400, 401, 404, 405);
        if (status != 400) {
            assertThat(status).as("POST " + rawPath + " must be refused by the platform, not by routing luck")
                    .isEqualTo(401);
        }
    }
}
