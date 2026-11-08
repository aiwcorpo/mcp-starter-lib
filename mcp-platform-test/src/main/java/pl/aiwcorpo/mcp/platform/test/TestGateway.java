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
 * Stands in for the platform gateway in a server's tests: serves the published test JWKS of mcp-contracts on {@code 127.0.0.1}, pushes the
 * policy data ({@link #policyData()}) to the server like the gateway does ({@code POST /.well-known/mcp-policy-data}, C14) and mints
 * internal JWTs (C2, with their purpose {@code use}) signed with the published test key A.
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
    /** The {@code prv} of a token: the revision a test gateway starts with, so a call asks the server for no refresh. */
    public static final long DEFAULT_POLICY_REVISION = PolicyDataEndpoint.INITIAL_REVISION;

    /** Purposes of a token (C2, claim {@code use}). */
    public static final String USE_CALL = "call";
    public static final String USE_MANIFEST = "manifest";
    public static final String USE_POLICY_DATA = "policy-data";

    /** Test property that names the gateway of a server under test, so that the server is attached when its web server starts. */
    static final String GATEWAY_PROPERTY = "aiwcorpo.mcp.platform.test.gateway";

    private static final Map<String, TestGateway> GATEWAYS = new java.util.concurrent.ConcurrentHashMap<>();

    private static final String KEYS = "mcp-contracts/test-vectors/keys/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String serverId;
    private final HttpServer server;
    private final ECKey signingKey;
    private final String key = UUID.randomUUID().toString();
    private PolicyDataEndpoint policyData;

    private TestGateway(String serverId, HttpServer server, ECKey signingKey) {
        this.serverId = serverId;
        this.server = server;
        this.signingKey = signingKey;
    }

    /**
     * The policy data this gateway pushes to the server: the scope mapping (group to scopes), the allowed AI clients and the emergency
     * overrides. Change it in a test to see the server follow: every change is pushed at once.
     */
    public PolicyDataEndpoint policyData() {
        return policyData;
    }

    /** Starts the JWKS endpoint for a server with id {@code serverId} ({@code mcp-<system>}). */
    public static TestGateway start(String serverId) {
        return start(serverId, 0);
    }

    /**
     * As {@link #start(String)} on a fixed loopback port: {@link LocalGateway} keeps the JWKS address stable across its
     * restarts, so a running server does not lose the keys. Port 0 picks a free one.
     */
    public static TestGateway start(String serverId, int port) {
        try {
            byte[] jwks = resource(KEYS + "test-gateway-jwks.json");
            ECKey key = ECKey.parse(new String(resource(KEYS + "test-gateway-jwt-a.NOT-FOR-PRODUCTION.private.jwk.json"),
                    StandardCharsets.UTF_8));
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            server.createContext("/.well-known/mcp-jwks.json", exchange -> {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(jwks);
                }
            });
            TestGateway gateway = new TestGateway(serverId, server, key);
            gateway.policyData = new PolicyDataEndpoint(gateway, ownPolicies());
            GATEWAYS.put(gateway.key, gateway);
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

    /** The policies of the project under test (its {@code mcp/policies/}), or none when the classpath has none. */
    private static pl.aiwcorpo.mcp.platform.policy.ServerPolicies ownPolicies() {
        try {
            return pl.aiwcorpo.mcp.platform.policy.ServerPolicies.load(TestGateway.class.getClassLoader());
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    public String serverId() {
        return serverId;
    }

    public String jwksUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/.well-known/mcp-jwks.json";
    }

    /**
     * Properties for the server under test: server id, issuer, this JWKS, test keys allowed, in-memory jti
     * store, a portal link for {@code credential_required}, the manifest data and the name of this gateway, by which the server is
     * attached (and gets its first policy data) when its web server starts.
     */
    public Map<String, String> serverProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("aiwcorpo.mcp.platform.server-id", serverId);
        properties.put("aiwcorpo.mcp.platform.internal-jwt.issuer", ISSUER);
        properties.put("aiwcorpo.mcp.platform.internal-jwt.jwks-uri", jwksUri());
        properties.put("aiwcorpo.mcp.platform.internal-jwt.allow-test-keys", "true");
        properties.put("aiwcorpo.mcp.platform.internal-jwt.replay-store", "memory");
        properties.put("aiwcorpo.mcp.platform.credential.connect-url", "http://localhost:18090/connections?system={system}");
        properties.put("aiwcorpo.mcp.platform.manifest.version", "1.0.0");
        properties.put(GATEWAY_PROPERTY, key);
        properties.put("aiwcorpo.mcp.platform.manifest.accepted-credential-types", "user-secret,user-oauth");
        return properties;
    }

    /** A token of the user variant, as the gateway sends for {@code tools/call} (set {@link Token#tool}). */
    public Token user() {
        return new Token(this, false);
    }

    /** A token of the service variant ({@code sub=system:mcp-gateway}, no user, no tool); its purpose is {@code call} unless {@link Token#use} says otherwise. */
    public Token service() {
        return new Token(this, true);
    }

    /** A service token for {@code GET /.well-known/mcp-manifest}: the purpose {@code manifest}. */
    public Token manifestToken() {
        return service().use(USE_MANIFEST);
    }

    /** The gateway of the server whose properties carry {@code key}, empty when no gateway of this JVM has it. */
    static java.util.Optional<TestGateway> byKey(String key) {
        return java.util.Optional.ofNullable(key == null ? null : GATEWAYS.get(key));
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
        GATEWAYS.remove(key);
        server.stop(0);
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
        private final Map<String, Object> extraClaims = new LinkedHashMap<>();
        private Long mfa;
        private String audience;
        private String issuer = ISSUER;
        private String requestId = RequestIds.newRequestId();
        private String jti = UUID.randomUUID().toString();
        private Instant issuedAt = Instant.now();
        private Duration lifetime = Duration.ofSeconds(60);
        private long policyRevision = DEFAULT_POLICY_REVISION;
        private String use = USE_CALL;

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

        /**
         * A claim outside the contract, for the tests of the rejection: the schema allows no other claims, so a token with one
         * ends as {@code 401 invalid_claims}. Claims of the contract keep their own methods and win over this one.
         */
        public Token extraClaim(String name, Object value) {
            extraClaims.put(name, value);
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

        public Token policyRevision(long value) {
            policyRevision = value;
            return this;
        }

        /**
         * The purpose of the token ({@code use}): {@link #USE_CALL} (the default) for the MCP endpoint, {@link #USE_MANIFEST} and
         * {@link #USE_POLICY_DATA} for the two well-known endpoints, both only with a service token. Any other value is for a test of the refusal.
         */
        public Token use(String value) {
            use = value;
            return this;
        }

        public Minted mint() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();
            extraClaims.forEach(claims::claim);
            claims.issuer(issuer)
                    .claim("aud", audience)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(issuedAt.plus(lifetime)))
                    .jwtID(jti)
                    .claim("use", use)
                    .claim("rid", requestId)
                    .claim("prv", policyRevision);
            if (service) {
                claims.subject(SERVICE_SUBJECT).claim("grp", List.of());
            } else {
                claims.subject(subject).claim("usr", username).claim("grp", groups).claim("azp", client);
                if (tool != null) {
                    claims.claim("tool", tool);
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
