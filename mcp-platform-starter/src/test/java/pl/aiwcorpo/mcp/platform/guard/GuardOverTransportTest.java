package pl.aiwcorpo.mcp.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.security.CredentialContext;

/**
 * The guard end to end: a real MCP client, the real streamable-HTTP transport, real tool calls.
 *
 * <p>Here for the same reason as {@code CredentialPassthroughTest}: the guard reads the caller from
 * a ThreadLocal filled by a servlet filter, publishes hints by rewriting Spring AI's tool list and
 * confirms destructive calls through an MCP round trip. None of that is visible to a unit test that
 * calls a {@code ToolCallback} directly, and all of it breaks silently if the framework underneath
 * changes. One test, in the platform, for every server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.ai.mcp.server.protocol=STREAMABLE",
                "spring.ai.mcp.server.type=SYNC",
                "spring.ai.mcp.server.name=platform-guard-test",
                "spring.ai.mcp.server.streamable-http.mcp-endpoint=/mcp/test",
                "aiwcorpo.mcp.platform.guard.reload-interval=0ms"
        })
@DisplayName("the tool guard over the real transport")
class GuardOverTransportTest {

    private static final Path POLICY_FILE = createPolicyFile();

    /** Stands in for Keycloak / Entra ID: real discovery document, real JWKS, real signatures. */
    private static final TestIdentityProvider IDP = TestIdentityProvider.start();

    private static final String POLICY = """
            roles:
              mcp-operator: { max-risk: update }
              mcp-admin: { max-risk: destructive }
            """;

    /** anna: a valid token and no role. karol: an administrator. */
    private static String annaToken() {
        return IDP.token("anna");
    }

    private static String karolToken() {
        return IDP.token("karol", "mcp-admin");
    }

    @LocalServerPort
    int port;

    @Autowired
    Vault vault;

    @Autowired
    CapturedCodes codes;

    @DynamicPropertySource
    static void policyFile(DynamicPropertyRegistry registry) {
        registry.add("aiwcorpo.mcp.platform.guard.policy-file", () -> "file:" + POLICY_FILE);
        // issuer only: the signing keys are found through the provider's discovery document
        registry.add("aiwcorpo.mcp.platform.guard.oidc.issuer-uri", IDP::issuer);
        registry.add("aiwcorpo.mcp.platform.guard.oidc.audiences", () -> TestIdentityProvider.AUDIENCE);
    }

    @BeforeEach
    void reset() throws IOException {
        writePolicy(POLICY);
        vault.reset();
        codes.sent.clear();
    }

    // ------------------------------------------------------------------------------- the server

    @SpringBootApplication
    static class TestServer {

        @Bean
        Vault vault() {
            return new Vault();
        }

        @Bean
        ToolCallbackProvider tools(Vault vault) {
            return MethodToolCallbackProvider.builder().toolObjects(vault).build();
        }

        /** Stands in for the phone in the user's pocket: the test reads the code from here. */
        @Bean
        CapturedCodes confirmationCodeSender() {
            return new CapturedCodes();
        }
    }

    static class CapturedCodes implements ConfirmationCodeSender {

        final List<String> sent = new ArrayList<>();

        @Override
        public void send(CallerIdentity identity, String tool, String summary, String code, Instant expiresAt) {
            sent.add(code);
        }

        String last() {
            return sent.get(sent.size() - 1);
        }
    }

    /** One tool per risk level, over state the test can inspect. */
    static class Vault {

        final List<String> secrets = new ArrayList<>();
        final AtomicInteger destroyCalls = new AtomicInteger();

        void reset() {
            secrets.clear();
            secrets.addAll(List.of("alpha", "beta"));
            destroyCalls.set(0);
        }

        @Tool(name = "vault_list", description = "Lists the entries.")
        @ToolRisk(RiskLevel.READ_ONLY)
        public List<String> list() {
            return List.copyOf(secrets);
        }

        @Tool(name = "vault_backend_token", description = "Returns the token an adapter would send to the backend.")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String backendToken() {
            return CredentialContext.requireBackendToken("vault");
        }

        @Tool(name = "vault_add", description = "Adds an entry.")
        @ToolRisk(RiskLevel.UPDATE)
        public String add(@ToolParam(description = "entry name") String name) {
            secrets.add(name);
            return "added " + name;
        }

        @Tool(name = "vault_destroy", description = "Removes every entry. Cannot be undone.")
        @ToolRisk(RiskLevel.DESTRUCTIVE)
        public String destroy(@ToolParam(description = "must be the vault's name") String vault) {
            destroyCalls.incrementAndGet();
            secrets.clear();
            return "destroyed " + vault;
        }
    }

    // --------------------------------------------------------------------------------- the tests

    @Test
    @DisplayName("publishes each tool's flag as MCP hints, and the code argument where it can be needed")
    void toolListCarriesHintsAndTheConfirmationArgument() {
        try (McpSyncClient client = connect(karolToken(), null)) {
            Map<String, McpSchema.Tool> tools = new java.util.HashMap<>();
            client.listTools().tools().forEach(tool -> tools.put(tool.name(), tool));

            assertThat(tools).containsKeys("vault_list", "vault_backend_token", "vault_add", "vault_destroy", "platform_whoami");
            assertThat(tools.get("vault_list").annotations().readOnlyHint()).isTrue();
            assertThat(tools.get("vault_list").annotations().destructiveHint()).isFalse();
            assertThat(tools.get("vault_add").annotations().readOnlyHint()).isFalse();
            assertThat(tools.get("vault_add").annotations().destructiveHint()).isFalse();
            assertThat(tools.get("vault_destroy").annotations().destructiveHint()).isTrue();

            assertThat(properties(tools.get("vault_list"))).doesNotContainKey("confirmation_code");
            assertThat(properties(tools.get("vault_destroy"))).containsKeys("vault", "confirmation_code");
        }
    }

    @Test
    @DisplayName("a read-only caller reads, and is refused everything else")
    void aReadOnlyCallerIsHeldToReadOnlyTools() {
        try (McpSyncClient anna = connect(annaToken(), null)) {
            assertThat(text(call(anna, "vault_list", Map.of()))).contains("alpha", "beta");

            McpSchema.CallToolResult refused = call(anna, "vault_add", Map.of("name", "gamma"));
            assertThat(refused.isError()).isTrue();
            assertThat(text(refused)).contains("access_denied", "anna", "read-only");
            assertThat(vault.secrets).as("the tool never ran").containsExactly("alpha", "beta");
        }
    }

    @Test
    @DisplayName("no credentials means anonymous and read-only; a bad token means 401")
    void anonymousIsReadOnlyAndABadTokenIsRejected() throws Exception {
        try (McpSyncClient nobody = connect(null, null)) {
            assertThat(text(call(nobody, "platform_whoami", Map.of())))
                    .contains("\"subject\":\"anonymous\"", "\"authenticated\":false", "\"maxRisk\":\"read-only\"");
            assertThat(text(call(nobody, "vault_add", Map.of("name", "gamma")))).contains("access_denied");
        }

        HttpResponse<String> response = post("not-a-real-token");
        assertThat(response.statusCode()).as("never silently downgraded to anonymous").isEqualTo(401);
        assertThat(response.body()).doesNotContain("not-a-real-token");
    }

    @Test
    @DisplayName("only tokens this provider issued, for this server, still in date, are accepted")
    void tokensAreValidatedNotJustParsed() throws Exception {
        assertThat(post(karolToken()).statusCode()).as("the control: a good token gets in").isNotEqualTo(401);

        assertThat(post(IDP.forgedToken("karol", "mcp-admin")).statusCode())
                .as("right claims, signed by someone else's key").isEqualTo(401);
        assertThat(post(IDP.token(claims -> claims.subject("karol").claim("roles", List.of("mcp-admin"))
                .audience("some-other-application"))).statusCode())
                .as("issued by our provider, but for another application").isEqualTo(401);
        assertThat(post(IDP.token(claims -> claims.subject("karol").claim("roles", List.of("mcp-admin"))
                .expirationTime(java.util.Date.from(Instant.now().minusSeconds(600))))).statusCode())
                .as("expired").isEqualTo(401);
        assertThat(post(IDP.token(claims -> claims.subject("karol").claim("roles", List.of("mcp-admin"))
                .issuer("https://evil.example/realms/test"))).statusCode())
                .as("another issuer").isEqualTo(401);

        HttpResponse<String> rejected = post("garbage");
        assertThat(rejected.headers().firstValue("WWW-Authenticate")).get().asString()
                .as("the 401 tells an MCP client where to find the authorization server")
                .contains("Bearer", "error=\"invalid_token\"",
                        "resource_metadata=\"http://127.0.0.1:" + port + "/.well-known/oauth-protected-resource\"");
    }

    @Test
    @DisplayName("OAuth mode: the caller's own access token is what reaches the backend; X-PAT is not read")
    void theAccessTokenIsForwardedAndPatHeadersAreIgnored() {
        String token = karolToken();
        try (McpSyncClient karol = connect(token, null)) {
            assertThat(text(call(karol, "vault_backend_token", Map.of()))).isEqualTo("\"" + token + "\"");
        }
        try (McpSyncClient nobody = connect(null, null, "X-PAT-Vault", "a-personal-token")) {
            assertThat(text(call(nobody, "vault_backend_token", Map.of())))
                    .as("a PAT is not a credential in OAuth mode")
                    .contains("missing_credential", "Authorization: Bearer")
                    .doesNotContain("a-personal-token");
            assertThat(text(call(nobody, "platform_whoami", Map.of()))).contains("\"subject\":\"anonymous\"");
        }
    }

    @Test
    @DisplayName("publishes protected-resource metadata naming the identity provider")
    void servesProtectedResourceMetadata() throws Exception {
        for (String path : List.of("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp/test")) {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as("readable without a token").isEqualTo(200);
            assertThat(response.body())
                    .contains("\"resource\":\"http://127.0.0.1:" + port + "/mcp/test\"")
                    .contains("\"authorization_servers\":[\"" + IDP.issuer() + "\"]");
        }
    }

    @Test
    @DisplayName("finds roles where the provider puts them (Keycloak realm roles, OAuth scopes)")
    void readsRolesFromProviderSpecificClaims() {
        String keycloakStyle = IDP.token(claims -> claims.subject("ola")
                .claim("realm_access", TestIdentityProvider.realmAccess("offline_access", "mcp-operator")));
        try (McpSyncClient ola = connect(keycloakStyle, null)) {
            assertThat(text(call(ola, "vault_add", Map.of("name", "gamma")))).contains("added gamma");
        }
        String scopeStyle = IDP.token(claims -> claims.subject("svc").claim("scope", "openid profile mcp-operator"));
        try (McpSyncClient service = connect(scopeStyle, null)) {
            assertThat(text(call(service, "vault_add", Map.of("name", "delta")))).contains("added delta");
        }
    }

    @Test
    @DisplayName("whoami answers 'who am I and what may I do' before anything is attempted")
    void whoAmIReportsTheCallersPermissions() {
        try (McpSyncClient anna = connect(annaToken(), null)) {
            String answer = text(call(anna, "platform_whoami", Map.of()));

            assertThat(answer).contains("\"subject\":\"anna\"", "\"authenticated\":true");
            assertThat(answer).contains("{\"tool\":\"vault_list\",\"risk\":\"read-only\",\"allowed\":true");
            assertThat(answer).contains("{\"tool\":\"vault_destroy\",\"risk\":\"destructive\",\"allowed\":false");
        }
    }

    @Test
    @DisplayName("destructive, client can prompt: the call waits for the user's code, then runs")
    void destructiveCallWaitsForTheUsersCode() {
        List<String> prompts = new ArrayList<>();
        try (McpSyncClient karol = connect(karolToken(), message -> {
            prompts.add(message);
            return codes.last();                                   // the user types the code they received
        })) {
            McpSchema.CallToolResult result = call(karol, "vault_destroy", Map.of("vault", "main"));

            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(text(result)).contains("destroyed main");
            assertThat(vault.secrets).isEmpty();
            assertThat(prompts).singleElement().satisfies(prompt -> {
                assertThat(prompt).contains("vault_destroy", "main", "karol");
                assertThat(prompt).as("the code is not in the prompt").doesNotContain(codes.last());
            });
        }
    }

    @Test
    @DisplayName("destructive, wrong code or user declines: nothing runs")
    void aWrongCodeOrADeclineLeavesEverythingIntact() {
        try (McpSyncClient karol = connect(karolToken(), message -> wrongCode())) {
            McpSchema.CallToolResult result = call(karol, "vault_destroy", Map.of("vault", "main"));
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("confirmation_failed");
        }
        try (McpSyncClient karol = connect(karolToken(), message -> null)) {       // null = user pressed "decline"
            McpSchema.CallToolResult result = call(karol, "vault_destroy", Map.of("vault", "main"));
            assertThat(text(result)).contains("confirmation_declined");
        }
        assertThat(vault.destroyCalls).hasValue(0);
        assertThat(vault.secrets).containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("destructive, client cannot prompt: refused, then accepted on a second call with the code")
    void aClientWithoutElicitationUsesTheTwoCallFlow() {
        try (McpSyncClient karol = connect(karolToken(), null)) {
            McpSchema.CallToolResult first = call(karol, "vault_destroy", Map.of("vault", "main"));
            assertThat(first.isError()).isTrue();
            assertThat(text(first)).contains("confirmation_required").doesNotContain(codes.last());
            assertThat(vault.destroyCalls).hasValue(0);

            McpSchema.CallToolResult otherTarget = call(karol, "vault_destroy",
                    Map.of("vault", "backup", "confirmation_code", codes.last()));
            assertThat(text(otherTarget))
                    .as("the code was issued for vault 'main' only").contains("confirmation_failed");
            assertThat(vault.destroyCalls).hasValue(0);

            McpSchema.CallToolResult second = call(karol, "vault_destroy",
                    Map.of("vault", "main", "confirmation_code", codes.last()));
            assertThat(text(second)).contains("destroyed main");
            assertThat(vault.destroyCalls).hasValue(1);

            McpSchema.CallToolResult replay = call(karol, "vault_destroy",
                    Map.of("vault", "main", "confirmation_code", codes.last()));
            assertThat(text(replay)).as("a code works once").contains("confirmation_failed");
            assertThat(vault.destroyCalls).hasValue(1);
        }
    }

    @Test
    @DisplayName("a read-only caller is refused a destructive tool before any code is sent")
    void privilegeIsCheckedBeforeConfirmation() {
        try (McpSyncClient anna = connect(annaToken(), message -> "000-000")) {
            assertThat(text(call(anna, "vault_destroy", Map.of("vault", "main")))).contains("access_denied");
            assertThat(codes.sent).as("no MFA spam for a call that was never going to be allowed").isEmpty();
        }
    }

    @Test
    @DisplayName("editing the policy file takes effect on the next call, without a restart")
    void policyChangesApplyLive() throws IOException {
        try (McpSyncClient karol = connect(karolToken(), null);
             McpSyncClient anna = connect(annaToken(), null)) {
            assertThat(text(call(karol, "vault_add", Map.of("name", "gamma")))).contains("added gamma");

            writePolicy(POLICY + """
                    tools:
                      vault_add: { enabled: false }
                    """);
            assertThat(text(call(karol, "vault_add", Map.of("name", "delta"))))
                    .as("kill switch").contains("tool_disabled");

            writePolicy(POLICY + "authenticated: update\n");
            assertThat(text(call(anna, "vault_add", Map.of("name", "epsilon"))))
                    .as("every authenticated caller was promoted, the tool was re-enabled").contains("added epsilon");

            assertThat(vault.secrets).containsExactly("alpha", "beta", "gamma", "epsilon");
        }
    }

    // --------------------------------------------------------------------------------- plumbing

    /**
     * @param token   bearer token, or {@code null} for an anonymous client
     * @param answers how the "user" answers a confirmation prompt (return {@code null} to decline);
     *                {@code null} for a client that does not support elicitation at all
     */
    private McpSyncClient connect(String token, java.util.function.Function<String, String> answers) {
        return connect(token, answers, null, null);
    }

    private McpSyncClient connect(String token, java.util.function.Function<String, String> answers,
                                  String extraHeader, String extraValue) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port)
                .endpoint("/mcp/test")
                .httpRequestCustomizer((request, method, uri, body, context) -> {
                    if (token != null) {
                        request.header("Authorization", "Bearer " + token);
                    }
                    if (extraHeader != null) {
                        request.header(extraHeader, extraValue);
                    }
                })
                .build();
        McpClient.SyncSpec spec = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20));
        if (answers != null) {
            spec.capabilities(McpSchema.ClientCapabilities.builder().elicitation().build())
                    .elicitation(request -> {
                        String code = answers.apply(request.message());
                        return code == null
                                ? new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.DECLINE, null)
                                : new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.ACCEPT, Map.of("code", code));
                    });
        }
        McpSyncClient client = spec.build();
        client.initialize();
        return client;
    }

    /** One raw MCP request carrying {@code bearer}; only the HTTP status and headers matter. */
    private HttpResponse<String> post(String bearer) throws IOException, InterruptedException {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp/test"))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Authorization", "Bearer " + bearer)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static McpSchema.CallToolResult call(McpSyncClient client, String tool, Map<String, Object> arguments) {
        return client.callTool(McpSchema.CallToolRequest.builder(tool).arguments(arguments).build());
    }

    private static String text(McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent t) {
                text.append(t.text());
            }
        }
        return text.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(McpSchema.Tool tool) {
        return (Map<String, Object>) tool.inputSchema().get("properties");
    }

    private String wrongCode() {
        return codes.last().startsWith("0") ? "111-111" : "000-000";
    }

    private static Path createPolicyFile() {
        try {
            Path file = Files.createTempFile("guard-policy", ".yml");
            Files.writeString(file, "anonymous: read-only\n");
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final AtomicInteger EDITS = new AtomicInteger();

    /** Every write gets a distinct modification time, however coarse the filesystem clock is. */
    private static void writePolicy(String yaml) throws IOException {
        Files.writeString(POLICY_FILE, yaml);
        Files.setLastModifiedTime(POLICY_FILE, java.nio.file.attribute.FileTime.fromMillis(
                System.currentTimeMillis() + 2_000L * EDITS.incrementAndGet()));
    }
}
