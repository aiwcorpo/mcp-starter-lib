package pl.aiwcorpo.mcp.platform.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one MCP server alone, without the platform: stands in for the gateway on the developer's machine. It serves the
 * test JWKS, pushes the policy data (scope mapping, allowed clients) of {@link TestGateway} to the server like the gateway does
 * ({@code POST /.well-known/mcp-policy-data}, at once and every few seconds, so a server that is started later or restarted gets it too)
 * and forwards {@code /mcp} to the server, turning the client's {@code Authorization} header (the user's own PAT or token) into the
 * backend credential (C3) and adding an internal JWT (C2, purpose {@code call}) for a fixed local user. A call that the server answers
 * {@code 409 policy_data_stale} is repeated once after a push, as the gateway does.
 *
 * <p>Everything here is signed with the published test keys of mcp-contracts, which only the {@code dev} profile accepts,
 * and the {@code dev} profile refuses to start in a container or under systemd. So this cannot stand in for the gateway of
 * an installation. It also does none of the gateway's work: no login, no tool approval, no confirmation of destructive
 * tools. Calls reach the backend with the full rights of the token the client sends.
 *
 * <pre>
 * java -jar mcp-platform-test-local-gateway.jar --server mcp-jira
 * </pre>
 */
public final class LocalGateway {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** How often the policy data is pushed again: the gateway does it at most every 60 s, a local run is quicker so a restarted server is not left waiting. */
    private static final Duration PUSH_INTERVAL = Duration.ofSeconds(3);

    /** Request headers of the MCP Streamable HTTP transport that the server needs to see. */
    private static final List<String> FORWARDED_REQUEST_HEADERS = List.of("Content-Type", "Accept", "Mcp-Session-Id",
            "MCP-Protocol-Version", "Last-Event-ID");
    private static final List<String> FORWARDED_RESPONSE_HEADERS = List.of("Content-Type", "Mcp-Session-Id",
            "MCP-Protocol-Version", "Cache-Control");

    private final TestGateway gateway;
    private final URI upstream;
    private final String username;
    private final List<String> groups;
    private final String credentialType;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    private LocalGateway(TestGateway gateway, URI upstream, String username, List<String> groups, String credentialType) {
        this.gateway = gateway;
        this.upstream = upstream;
        this.username = username;
        this.groups = groups;
        this.credentialType = credentialType;
    }

    public static void main(String[] args) throws IOException {
        Map<String, String> options = parse(args);
        String serverId = options.get("server");
        if (serverId == null || !serverId.matches("mcp-[a-z][a-z0-9]{1,29}")) {
            usage("--server mcp-<system> is required");
        }
        String system = serverId.substring("mcp-".length());
        int listen = port(options.getOrDefault("listen", "18500"));
        int jwksPort = port(options.getOrDefault("jwks-port", "18501"));
        URI upstream = URI.create(options.getOrDefault("upstream", "http://127.0.0.1:8080/mcp"));
        List<String> groups = options.containsKey("groups")
                ? Arrays.stream(options.get("groups").split(",")).map(String::strip).filter(g -> !g.isEmpty()).toList()
                : List.of(serverId + "-read", serverId + "-write", serverId + "-destructive");
        String credentialType = options.getOrDefault("credential-type", "user-secret");
        Path envFile = Path.of(options.getOrDefault("env-file", "local-gateway.env")).toAbsolutePath();

        TestGateway gateway = TestGateway.start(serverId, jwksPort);
        Path policiesDir = Path.of(options.getOrDefault("policies-dir", "src/main/resources/mcp/policies"));
        if (Files.isDirectory(policiesDir)) {
            gateway.policyData().policies(readPolicies(policiesDir));
        } else {
            System.err.println("No policies at " + policiesDir.toAbsolutePath() + " (--policies-dir): the policy data approves none, so the server "
                    + "refuses every call.");
        }
        if (options.containsKey("scope-mapping")) {
            gateway.policyData().scopeMapping(readScopeMapping(Path.of(options.get("scope-mapping"))));
        }
        if (options.containsKey("allowed-clients")) {
            gateway.policyData().allowedClients(Arrays.stream(options.get("allowed-clients").split(",")).map(String::strip)
                    .filter(client -> !client.isEmpty()).toArray(String[]::new));
        }
        Map<String, String> properties = new LinkedHashMap<>(gateway.serverProperties());
        properties.put("aiwcorpo.mcp.platform.manifest.accepted-credential-types", credentialType);
        // The server of this run is not a Spring test: it is attached here, by its address, not by the test property.
        properties.remove("aiwcorpo.mcp.platform.test.gateway");
        Files.writeString(envFile, "SPRING_PROFILES_ACTIVE=dev\nSPRING_APPLICATION_JSON='"
                + JSON.writeValueAsString(properties) + "'\n", StandardCharsets.UTF_8);

        LocalGateway local = new LocalGateway(gateway, upstream, options.getOrDefault("user", "local-user"), groups,
                credentialType);
        gateway.policyData().attach(upstream);
        Thread pusher = Thread.ofVirtual().name("local-gateway-push").unstarted(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(PUSH_INTERVAL.toMillis());
                } catch (InterruptedException ex) {
                    return;
                }
                gateway.policyData().push();
            }
        });
        pusher.setDaemon(true);
        pusher.start();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), listen), 0);
        server.createContext("/mcp", local::forward);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.printf("""
                Local gateway for %s
                  server settings: %s
                  start the server: set -a; . %s; set +a; java -jar <path>/%s.jar
                                    (plus the address of the system, e.g. MCP_%s_URL=https://...)
                  MCP endpoint:     http://127.0.0.1:%d/mcp, header Authorization: <scheme> <token>, sent to the system as %s
                  forwards to:      %s as user %s, groups %s
                Test keys and no gateway checks: for a developer's machine only.
                """, serverId, envFile, envFile, serverId, system.toUpperCase(java.util.Locale.ROOT), listen, credentialType,
                upstream, local.username, String.join(",", groups));
    }

    private void forward(HttpExchange exchange) throws IOException {
        try (exchange) {
            // A web page reaching this port through DNS rebinding sends its own Host; a page on another site sends its Origin.
            String host = exchange.getRequestHeaders().getFirst("Host");
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            if (host == null || !host.matches("(127\\.0\\.0\\.1|localhost)(:\\d+)?")
                    || origin != null && !origin.matches("https?://(127\\.0\\.0\\.1|localhost)(:\\d+)?")) {
                reply(exchange, 403, "{\"error\":\"only local clients: Host and Origin must be 127.0.0.1 or localhost\"}");
                return;
            }
            String credential = exchange.getRequestHeaders().getFirst("Authorization");
            if (credential == null || !credential.matches("(Bearer|Basic|Token|ApiKey) \\S.*")) {
                reply(exchange, 401, "{\"error\":\"send the token for the system as Authorization: Bearer <token> "
                        + "(or Basic, Token, ApiKey)\"}");
                return;
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            String tool = toolCalled(body);

            HttpResponse<InputStream> response;
            try {
                response = send(exchange, body, credential, tool);
                if (tool != null && response.statusCode() == 409) {
                    byte[] refusal = response.body().readAllBytes();
                    if (new String(refusal, StandardCharsets.UTF_8).contains("\"policy_data_stale\"")) {
                        // C14: the server is behind (restarted, or it missed a push): push, then repeat the call once with a new token.
                        gateway.policyData().push();
                        response = send(exchange, body, credential, tool);
                    } else {
                        response = new Replayed(response, refusal);
                    }
                }
            } catch (IOException ex) {
                reply(exchange, 502, "{\"error\":\"the server at " + upstream + " does not answer: " + ex.getMessage() + "\"}");
                return;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            for (String name : FORWARDED_RESPONSE_HEADERS) {
                response.headers().firstValue(name).ifPresent(value -> exchange.getResponseHeaders().set(name, value));
            }
            boolean noBody = response.statusCode() == 204 || response.statusCode() == 304
                    || "HEAD".equals(exchange.getRequestMethod());
            exchange.sendResponseHeaders(response.statusCode(), noBody ? -1 : 0);
            try (InputStream in = response.body(); OutputStream out = exchange.getResponseBody()) {
                if (noBody) {
                    return;
                }
                // Flushed per read: a tools/call answered as an event stream must reach the client as it arrives.
                byte[] buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) != -1; ) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            }
        }
    }

    /** One request to the server with a fresh internal JWT: every attempt has a new {@code jti}, and the request id of the token is the header. */
    private HttpResponse<InputStream> send(HttpExchange exchange, byte[] body, String credential, String tool)
            throws IOException, InterruptedException {
        TestGateway.Token token = gateway.user().username(username).groups(groups).client("local-gateway").mfaAgeSeconds(0);
        if (tool != null) {
            token.tool(tool);
        }
        TestGateway.Minted minted = token.mint();
        HttpRequest.Builder request = HttpRequest.newBuilder(upstream).timeout(Duration.ofHours(1))
                .method(exchange.getRequestMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        for (String name : FORWARDED_REQUEST_HEADERS) {
            String value = exchange.getRequestHeaders().getFirst(name);
            if (value != null) {
                request.header(name, value);
            }
        }
        minted.headers().forEach(request::header);
        request.header("X-MCP-Credential", credential);
        request.header("X-MCP-Credential-Type", credentialType);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    /** An answer whose body was already read (to look at it) and is given back to the client as it was. */
    private record Replayed(HttpResponse<InputStream> original, byte[] content) implements HttpResponse<InputStream> {

        @Override
        public int statusCode() {
            return original.statusCode();
        }

        @Override
        public HttpRequest request() {
            return original.request();
        }

        @Override
        public java.util.Optional<HttpResponse<InputStream>> previousResponse() {
            return java.util.Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
            return original.headers();
        }

        @Override
        public InputStream body() {
            return new java.io.ByteArrayInputStream(content);
        }

        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() {
            return java.util.Optional.empty();
        }

        @Override
        public URI uri() {
            return original.uri();
        }

        @Override
        public HttpClient.Version version() {
            return original.version();
        }
    }

    private static String toolCalled(byte[] body) {
        if (body.length == 0) {
            return null;
        }
        try {
            JsonNode message = JSON.readTree(body);
            if (message.isObject() && "tools/call".equals(message.path("method").asString(null))) {
                return message.path("params").path("name").asString(null);
            }
        } catch (RuntimeException ex) {
            // Not JSON: the server answers it with its own parse error.
        }
        return null;
    }

    private static void reply(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** The policies of the server in its source tree: what the gateway would have approved from its manifest. */
    private static pl.aiwcorpo.mcp.platform.policy.ServerPolicies readPolicies(Path dir) throws IOException {
        Map<String, String> files = new java.util.TreeMap<>();
        try (var listing = Files.list(dir)) {
            for (Path file : listing.filter(path -> path.getFileName().toString().endsWith(".cedar")).toList()) {
                files.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return pl.aiwcorpo.mcp.platform.policy.ServerPolicies.of(Files.readString(dir.resolve("schema.cedarschema"), StandardCharsets.UTF_8),
                files, Files.readString(dir.resolve("resource-types.json"), StandardCharsets.UTF_8));
    }

    /** The document of {@code scopeMapping} in the policy data: {@code [{"group": "mcp-x-team", "scopes": ["Type::Name/id"]}]}. */
    private static Map<String, List<String>> readScopeMapping(Path file) throws IOException {
        Map<String, List<String>> mapping = new LinkedHashMap<>();
        for (JsonNode entry : JSON.readTree(Files.readAllBytes(file))) {
            List<String> scopes = new java.util.ArrayList<>();
            entry.path("scopes").forEach(scope -> scopes.add(scope.asString()));
            mapping.put(entry.path("group").asString(), scopes);
        }
        return mapping;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                usage("unexpected argument " + args[i]);
            }
            options.put(args[i].substring(2), args[++i]);
        }
        return options;
    }

    private static int port(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            usage("not a port: " + value);
            return -1;
        }
    }

    private static void usage(String problem) {
        System.err.println(problem);
        System.err.println("""
                usage: java -jar mcp-platform-test-local-gateway.jar --server mcp-<system> [options]
                  --listen PORT            MCP endpoint for the client (18500)
                  --upstream URL           the server's /mcp in the dev profile (http://127.0.0.1:8080/mcp)
                  --jwks-port PORT         test JWKS and policy data for the server (18501)
                  --groups A,B             groups of the local user (mcp-<system>-read,-write,-destructive)
                  --credential-type TYPE   user-secret (default), user-oauth, token-exchange or service-account
                  --policies-dir PATH      the server's mcp/policies (src/main/resources/mcp/policies): its hash is the one the policy data approves
                  --scope-mapping PATH     JSON file with the scope mapping, [{"group": "...", "scopes": ["<Type>/<id>"]}] (the example mapping of mcp-contracts)
                  --allowed-clients A,B    AI clients the policy data allows (claude-code,claude-ai,local-gateway)
                  --user NAME              username in the internal JWT (local-user)
                  --env-file PATH          where to write the server settings (local-gateway.env)""");
        System.exit(2);
    }
}
