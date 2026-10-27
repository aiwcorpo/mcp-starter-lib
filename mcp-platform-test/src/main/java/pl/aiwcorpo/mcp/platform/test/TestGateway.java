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
 * the in-memory jti store and plain HTTP are refused otherwise.
 *
 * <p>The keys are public (mcp-contracts {@code test-vectors/keys}); this class exists for tests only.
 */
public final class TestGateway implements AutoCloseable {

    public static final String ISSUER = "https://mcp-gateway.internal";
    public static final String SERVICE_SUBJECT = "system:mcp-gateway";
    public static final String DEFAULT_POLICY_REVISION = "20261026120000-a1b2c3d";

    private static final String KEYS = "mcp-contracts/test-vectors/keys/";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final String serverId;
    private final HttpServer server;
    private final ECKey signingKey;

    private TestGateway(String serverId, HttpServer server, ECKey signingKey) {
        this.serverId = serverId;
        this.server = server;
        this.signingKey = signingKey;
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
            server.start();
            return new TestGateway(serverId, server, key);
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
     * store, no client certificate, and a portal link for {@code credential_required}.
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
                request.setHeader("X-MCP-Credential-Type", "user-secret");
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
