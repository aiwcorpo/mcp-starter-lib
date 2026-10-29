package pl.aiwcorpo.mcp.platform.test;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import pl.aiwcorpo.mcp.platform.observability.RequestIds;
import pl.aiwcorpo.mcp.platform.security.BackendCredential;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stands in for the platform gateway in a server's tests: serves the published test JWKS of mcp-contracts on
 * {@code 127.0.0.1} and mints internal JWTs (C2) signed with the published test key A.
 *
 * <pre>{@code
 * static final TestGateway GATEWAY = TestGateway.start("mcp-jira");
 *
 * @DynamicPropertySource
 * static void gateway(DynamicPropertyRegistry registry) {
 *     GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
 * }
 * }</pre>
 * The server under test must run with the {@code dev} profile ({@code @ActiveProfiles("dev")}): the test keys,
 * the in-memory jti store and plain HTTP are refused otherwise. The starter's "{@code dev} in a deployment" check does
 * not apply to a context built by Spring's test framework, so these tests also run in CI on a Kubernetes pod or under
 * a systemd runner; there is nothing to set for that.
 *
 * <p>The keys are public (mcp-contracts {@code test-vectors/keys}); this class exists for tests only.
 */
public final class TestGateway implements AutoCloseable {

    public static final String ISSUER = "https://mcp-gateway.internal";
    public static final String SERVICE_SUBJECT = "system:mcp-gateway";
    public static final String DEFAULT_POLICY_REVISION = "20261026120000-a1b2c3d";

    private static final java.util.Set<String> SYSTEM_SERVERS = java.util.Set.of("mcp-jira", "mcp-confluence", "mcp-gitlab",
            "mcp-servicedesk", "mcp-opensearch");
    private static final String KEYS = "mcp-contracts/test-vectors/keys/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String serverId;
    private final HttpServer server;
    private final ECKey signingKey;
    private final PolicyRepository main = new PolicyRepository();
    private final PolicyRepository overrides = new PolicyRepository();

    private TestGateway(String serverId, HttpServer server, ECKey signingKey) {
        this.serverId = serverId;
        this.server = server;
        this.signingKey = signingKey;
    }

    /** The main policy bundle channel the server pulls from; it starts with the example policies of the contracts. */
    public PolicyRepository main() {
        return main;
    }

    /**
     * The emergency policy bundle channel. The server is configured to pull it only if a bundle was published here
     * before {@link #serverProperties()} is called.
     */
    public PolicyRepository overrides() {
        return overrides;
    }

    /** Starts the JWKS endpoint for a server with id {@code serverId} ({@code mcp-<system>}). */
    public static TestGateway start(String serverId) {
        try {
            byte[] jwks = resource(KEYS + "test-gateway-jwks.json");
            ECKey key = ECKey.parse(new String(resource(KEYS + "test-gateway-jwt-a.NOT-FOR-PRODUCTION.private.jwk.json"),
                    StandardCharsets.UTF_8));
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/.well-known/jwks.json", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(jwks);
                }
            });
            TestGateway gateway = new TestGateway(serverId, server, key);
            if (SYSTEM_SERVERS.contains(serverId)) {
                gateway.main.publish("20261026130000-b2c3d4e");
            } else {
                // A server of none of the five systems (the template's mcp-example): the example policies of the contracts
                // know only those five, so the bundle is generated for this server id.
                gateway.main.publishGenerated("20261026130000-0e0e0e0", serverId);
            }
            server.createContext("/policies/main/", exchange -> gateway.main.handle(exchange,
                    exchange.getRequestURI().getPath().substring("/policies/main/".length())));
            server.createContext("/policies/overrides/", exchange -> gateway.overrides.handle(exchange,
                    exchange.getRequestURI().getPath().substring("/policies/overrides/".length())));
            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(task -> {
                Thread thread = new Thread(task, "test-gateway");
                thread.setDaemon(true);
                return thread;
            }));
            server.start();
            return gateway;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (ParseException ex) {
            throw new IllegalStateException("test key of mcp-contracts is not a valid JWK", ex);
        }
    }

    public String serverId() {
        return serverId;
    }

    public String jwksUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/jwks.json";
    }

    /**
     * Properties for the server under test: server id, issuer, this JWKS, test keys allowed, in-memory jti
     * store, no client certificate, a portal link for {@code credential_required}, the policy bundle channels of this
     * gateway ({@link #main()}, {@link #overrides()}, test keys allowed) and the manifest data.
     */
    public Map<String, String> serverProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("aiwcorpo.mcp.platform.server-id", serverId);
        properties.put("aiwcorpo.mcp.platform.internal-jwt.issuer", ISSUER);
        properties.put("aiwcorpo.mcp.platform.internal-jwt.jwks-uri", jwksUri());
        properties.put("aiwcorpo.mcp.platform.internal-jwt.allow-test-keys", "true");
        properties.put("aiwcorpo.mcp.platform.internal-jwt.replay-store", "memory");
        properties.put("aiwcorpo.mcp.platform.mtls.required", "false");
        properties.put("aiwcorpo.mcp.platform.credential.connect-url", "http://localhost:18090/connections?system={system}");
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/policies/";
        properties.put("aiwcorpo.mcp.platform.policy.main.url", base + "main/latest.json");
        properties.put("aiwcorpo.mcp.platform.policy.main.key-id", "test-policy-bundle");
        properties.put("aiwcorpo.mcp.platform.policy.main.public-key", pem("test-policy-bundle.public.pem"));
        properties.put("aiwcorpo.mcp.platform.policy.main.refresh-interval", "PT1S");
        properties.put("aiwcorpo.mcp.platform.policy.allow-test-keys", "true");
        properties.put("aiwcorpo.mcp.platform.policy.startup-timeout", "PT10S");
        if (overrides.revision() != null) {
            properties.put("aiwcorpo.mcp.platform.policy.overrides.url", base + "overrides/latest.json");
            properties.put("aiwcorpo.mcp.platform.policy.overrides.key-id", "test-policy-overrides");
            properties.put("aiwcorpo.mcp.platform.policy.overrides.public-key", pem("test-policy-overrides.public.pem"));
            properties.put("aiwcorpo.mcp.platform.policy.overrides.refresh-interval", "PT1S");
        }
        properties.put("aiwcorpo.mcp.platform.manifest.version", "1.0.0");
        properties.put("aiwcorpo.mcp.platform.manifest.internal-url", "https://" + serverId + ".internal:8443/mcp");
        properties.put("aiwcorpo.mcp.platform.manifest.accepted-credential-types", "user-secret,user-oauth");
        properties.put("aiwcorpo.mcp.platform.credential-type", "user-secret");
        return properties;
    }

    /** A token of the user variant, as the gateway sends for {@code tools/call} (set {@link Token#tool}). */
    public Token user() {
        return new Token(this, false);
    }

    /** A token of the service variant ({@code sub=system:mcp-gateway}, no user, no tool). */
    public Token service() {
        return new Token(this, true);
    }

    /**
     * An MCP client request customizer that behaves like the gateway: every HTTP request gets a fresh user
     * token, with {@code tool} set to {@code params.name} on {@code tools/call}, a matching
     * {@code X-Request-Id}, and the given backend credential headers (C3) when {@code credential} is not
     * {@code null} ({@code "Bearer <value>"} as {@code user-secret}).
     *
     * <pre>{@code
     * HttpClientStreamableHttpTransport.builder(url).endpoint("/mcp")
     *         .httpRequestCustomizer(GATEWAY.asGateway(user -> user.username("jkowalski"), "Bearer jira-pat"))
     * }</pre>
     */
    public McpSyncHttpClientRequestCustomizer asGateway(java.util.function.Consumer<Token> user, String credential) {
        return asGateway(user, credential, BackendCredential.Type.USER_SECRET);
    }

    /**
     * As {@link #asGateway(java.util.function.Consumer, String)} with the credential type the gateway would put
     * in {@code X-MCP-Credential-Type} ({@code user-oauth}, {@code user-secret}, {@code token-exchange} or
     * {@code service-account}); ignored when {@code credential} is {@code null}.
     */
    public McpSyncHttpClientRequestCustomizer asGateway(java.util.function.Consumer<Token> user, String credential,
                                                        BackendCredential.Type credentialType) {
        java.util.Objects.requireNonNull(credentialType, "credentialType");
        return (request, method, uri, body, context) -> {
            Token token = user();
            user.accept(token);
            String tool = toolCalled(body);
            if (tool != null) {
                token.tool(tool);
            }
            Minted minted = token.mint();
            minted.headers().forEach(request::setHeader);
            if (credential != null) {
                request.setHeader("X-MCP-Credential", credential);
                request.setHeader("X-MCP-Credential-Type", credentialType.wireName());
            }
        };
    }

    private static String toolCalled(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        JsonNode message = JSON.readTree(body);
        if (message.isObject() && "tools/call".equals(message.path("method").asString(null))) {
            return message.path("params").path("name").asString(null);
        }
        return null;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String pem(String file) {
        try {
            return new String(resource(KEYS + file), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = TestGateway.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " not found: add pl.aiwcorpo.mcp:mcp-contracts:test-vectors in test scope");
            }
            return in.readAllBytes();
        }
    }

    /** A signed token and the request id it is bound to. */
    public record Minted(String jwt, String requestId) {

        /** {@code Authorization} and {@code X-Request-Id}, as the gateway sets them. */
        public Map<String, String> headers() {
            return Map.of("Authorization", "Bearer " + jwt, "X-Request-Id", requestId);
        }
    }

    /** Builder of one internal JWT; defaults are a valid token for the gateway's server. */
    public static final class Token {

        private final TestGateway gateway;
        private final boolean service;
        private String subject = "6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11";
        private String username = "jkowalski";
        private List<String> groups = new ArrayList<>(List.of("mcp-jira-read"));
        private String client = "claude-code";
        private String tool;
        private String virtualServer;
        private Map<String, List<String>> virtualServerScopes;
        private Long mfa;
        private String audience;
        private String issuer = ISSUER;
        private String requestId = RequestIds.newRequestId();
        private String jti = UUID.randomUUID().toString();
        private Instant issuedAt = Instant.now();
        private Duration lifetime = Duration.ofSeconds(60);
        private String policyRevision = DEFAULT_POLICY_REVISION;

        private Token(TestGateway gateway, boolean service) {
            this.gateway = gateway;
            this.service = service;
            this.audience = gateway.serverId;
        }

        public Token subject(String value) {
            subject = value;
            return this;
        }

        public Token username(String value) {
            username = value;
            return this;
        }

        public Token groups(List<String> value) {
            groups = new ArrayList<>(value);
            return this;
        }

        public Token client(String value) {
            client = value;
            return this;
        }

        public Token tool(String value) {
            tool = value;
            return this;
        }

        /** {@code vs} and {@code vsc} together; an empty map is a link without narrowing. */
        public Token virtualServer(String slug, Map<String, List<String>> scopes) {
            virtualServer = slug;
            virtualServerScopes = scopes;
            return this;
        }

        public Token mfaAgeSeconds(long value) {
            mfa = value;
            return this;
        }

        public Token audience(String value) {
            audience = value;
            return this;
        }

        public Token issuer(String value) {
            issuer = value;
            return this;
        }

        public Token requestId(String value) {
            requestId = value;
            return this;
        }

        public Token jti(String value) {
            jti = value;
            return this;
        }

        public Token issuedAt(Instant value) {
            issuedAt = value;
            return this;
        }

        public Token lifetime(Duration value) {
            lifetime = value;
            return this;
        }

        public Token policyRevision(String value) {
            policyRevision = value;
            return this;
        }

        public Minted mint() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .claim("aud", audience)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(issuedAt.plus(lifetime)))
                    .jwtID(jti)
                    .claim("rid", requestId)
                    .claim("prv", policyRevision);
            if (service) {
                claims.subject(SERVICE_SUBJECT).claim("grp", List.of());
            } else {
                claims.subject(subject).claim("usr", username).claim("grp", groups).claim("azp", client);
                if (tool != null) {
                    claims.claim("tool", tool);
                }
                if (virtualServer != null) {
                    claims.claim("vs", virtualServer).claim("vsc", virtualServerScopes == null ? Map.of() : virtualServerScopes);
                }
                if (mfa != null) {
                    claims.claim("mfa", mfa);
                }
            }
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256)
                        .keyID(gateway.signingKey.getKeyID()).type(JOSEObjectType.JWT).build(), claims.build());
                jwt.sign(new ECDSASigner(gateway.signingKey));
                return new Minted(jwt.serialize(), requestId);
            } catch (JOSEException ex) {
                throw new IllegalStateException("cannot sign the test token", ex);
            }
        }
    }
}
