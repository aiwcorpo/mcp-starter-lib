package pl.aiwcorpo.mcp.platform.guard;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A minimal OIDC provider for tests: a discovery document, a JWKS endpoint and a way to mint signed
 * access tokens. It speaks real HTTP and signs with a real key, so the server under test validates
 * tokens exactly as it would against Keycloak or Entra ID.
 */
final class TestIdentityProvider implements AutoCloseable {

    static final String AUDIENCE = "mcp-test-server";

    private final HttpServer http;
    private final RSAKey key;
    private final String issuer;

    private TestIdentityProvider(HttpServer http, RSAKey key) {
        this.http = http;
        this.key = key;
        this.issuer = "http://127.0.0.1:" + http.getAddress().getPort() + "/realms/test";
    }

    static TestIdentityProvider start() {
        try {
            RSAKey key = new RSAKeyGenerator(2048).keyID("test-key").generate();
            HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            TestIdentityProvider idp = new TestIdentityProvider(http, key);
            serve(http, "/realms/test/.well-known/openid-configuration", "{\"issuer\":\"" + idp.issuer
                    + "\",\"jwks_uri\":\"" + idp.issuer + "/jwks\",\"id_token_signing_alg_values_supported\":[\"RS256\"],"
                    + "\"subject_types_supported\":[\"public\"]}");
            serve(http, "/realms/test/jwks", new JWKSet(key.toPublicJWK()).toString());
            http.start();
            return idp;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void serve(HttpServer http, String path, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        http.createContext(path, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
    }

    String issuer() {
        return issuer;
    }

    /** A valid access token for {@code subject} carrying {@code roles} in the top-level "roles" claim. */
    String token(String subject, String... roles) {
        return token(claims -> claims.subject(subject).claim("roles", List.of(roles)));
    }

    /** A token with the standard claims filled in, then adjusted by {@code customizer}. */
    String token(Consumer<JWTClaimsSet.Builder> customizer) {
        return sign(key, customizer);
    }

    /** A well-formed token signed by a key this provider does not publish. */
    String forgedToken(String subject, String... roles) {
        try {
            RSAKey other = new RSAKeyGenerator(2048).keyID("test-key").generate();
            return sign(other, claims -> claims.subject(subject).claim("roles", List.of(roles)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String sign(RSAKey signingKey, Consumer<JWTClaimsSet.Builder> customizer) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .audience(AUDIENCE)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(300)));
            customizer.accept(claims);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).type(JOSEObjectType.JWT).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Keycloak puts realm roles here rather than in a top-level claim. */
    static Map<String, Object> realmAccess(String... roles) {
        return Map.of("roles", List.of(roles));
    }

    @Override
    public void close() {
        http.stop(0);
    }
}
