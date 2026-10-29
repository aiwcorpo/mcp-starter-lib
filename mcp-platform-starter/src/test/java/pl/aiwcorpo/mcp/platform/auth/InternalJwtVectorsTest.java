package pl.aiwcorpo.mcp.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every JWT vector of mcp-contracts ({@code test-vectors/jwt}, read from the {@code test-vectors} jar) gives the
 * expected result and reason, first on the verifier and then, for vectors addressed to an MCP server, on the
 * HTTP filter with the request the vector describes.
 */
class InternalJwtVectorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ROOT = "mcp-contracts/test-vectors/";

    /** Files in test-vectors/jwt of mcp-contracts 3.0.2. */
    private static final int EXPECTED_VECTORS = 60;

    record Vector(String name, JsonNode data) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> vectors() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:" + ROOT + "jwt/*.json");
        List<Vector> vectors = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                vectors.add(new Vector(resource.getFilename(), JSON.readTree(in)));
            }
        }
        vectors.sort(Comparator.comparing(Vector::name));
        return vectors;
    }

    static Stream<Arguments> allVectors() throws IOException {
        return vectors().stream().map(Arguments::of);
    }

    static Stream<Arguments> serverVectors() throws IOException {
        return vectors().stream()
                .filter(v -> !"mcp-credential-broker".equals(v.data().get("verifier").get("audience").asString()))
                .map(Arguments::of);
    }

    @Test
    void theWholeDirectoryIsRead() throws IOException {
        assertThat(vectors()).hasSize(EXPECTED_VECTORS);
        Set<String> reasons = new java.util.TreeSet<>();
        vectors().forEach(v -> {
            JsonNode reason = v.data().get("expected").get("reason");
            if (reason != null) {
                reasons.add(reason.asString());
            }
        });
        assertThat(reasons).as("every rejection reason of the contract has a vector")
                .containsAll(Arrays.stream(InternalJwtRejection.values()).filter(r -> !r.infrastructure())
                        .filter(r -> r != InternalJwtRejection.MALFORMED
                                && r != InternalJwtRejection.CLIENT_CERTIFICATE_REJECTED)
                        .map(InternalJwtRejection::code).toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVectors")
    void verifierAgreesWithTheVector(Vector vector) throws Exception {
        JsonNode data = vector.data();
        JsonNode expected = data.get("expected");
        InternalJwtVerifier verifier = verifierFor(data.get("verifier"));
        String token = data.get("token").isNull() ? null : data.get("token").asString();
        RequestBinding binding = bindingFor(data.get("request"));

        if (expected.get("valid").asBoolean()) {
            CallerContext caller = verifier.verify(token, binding);
            JsonNode claims = data.get("claims");
            assertThat(caller.subject()).isEqualTo(claims.get("sub").asString());
            assertThat(caller.requestId()).isEqualTo(claims.get("rid").asString());
            assertThat(caller.policyRevision()).isEqualTo(claims.get("prv").asString());
            assertThat(caller.tool()).isEqualTo(claims.has("tool") ? claims.get("tool").asString() : null);
            assertThat(caller.virtualServer()).isEqualTo(claims.has("vs") ? claims.get("vs").asString() : null);
            assertThat(caller.virtualServerScopes() != null).isEqualTo(claims.has("vsc"));
            assertThat(expected.get("httpStatus").asInt()).isEqualTo(200);
        } else {
            assertThatThrownBy(() -> verifier.verify(token, binding))
                    .isInstanceOf(InternalJwtRejectedException.class)
                    .extracting(ex -> ((InternalJwtRejectedException) ex).reason().code())
                    .isEqualTo(expected.get("reason").asString());
            assertThat(expected.get("httpStatus").asInt()).isEqualTo(401);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serverVectors")
    void filterAnswersWithTheVectorsHttpStatus(Vector vector) throws Exception {
        JsonNode data = vector.data();
        InternalJwtFilter filter = new InternalJwtFilter(verifierFor(data.get("verifier")),
                List.of("/actuator/health"), 1024 * 1024, false, Set.of());
        MockHttpServletRequest request = httpRequestFor(data.get("request"));
        if (!data.get("token").isNull()) {
            request.addHeader("Authorization", "Bearer " + data.get("token").asString());
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        int expected = data.get("expected").get("httpStatus").asInt();
        if (expected == 200) {
            assertThat(chain.getRequest()).as("the request reached the MCP endpoint").isNotNull();
            assertThat(response.getStatus()).isEqualTo(200);
        } else {
            assertThat(chain.getRequest()).as("the request must not reach the MCP endpoint").isNull();
            assertThat(response.getStatus()).isEqualTo(expected);
            assertThat(response.getContentAsString()).contains("\"error\":\"unauthenticated\"");
        }
    }

    static InternalJwtVerifier verifierFor(JsonNode settings) throws Exception {
        Clock clock = Clock.fixed(Instant.parse(settings.get("now").asString()), ZoneOffset.UTC);
        InMemoryJtiReplayStore store = new InMemoryJtiReplayStore(clock);
        for (JsonNode seen : settings.get("seenJtis")) {
            store.firstUse(seen.asString(), clock.instant().plusSeconds(3600));
        }
        String jwks = read(ROOT + settings.get("jwks").asString());
        return new InternalJwtVerifier(new InternalJwtVerifier.Settings(
                settings.get("issuer").asString(), settings.get("audience").asString(),
                Duration.ofSeconds(settings.get("clockSkewSeconds").asLong()),
                Duration.ofSeconds(settings.get("maxLifetimeSeconds").asLong()),
                settings.get("allowTestKeys").asBoolean(),
                settings.path("devstackProfile").asBoolean(false)),
                JwksGatewayKeys.of(jwks), store, clock);
    }

    /** The binding a server (or, for the broker's vectors, the broker) derives from the request. */
    static RequestBinding bindingFor(JsonNode request) {
        String rid = request.get("xRequestId").asString();
        String method = request.get("method").asString();
        if ("jsonrpc".equals(request.get("kind").asString())) {
            if ("tools/call".equals(method)) {
                return RequestBinding.toolCall(request.get("toolName").asString(), rid);
            }
            return McpRequestClassifier.PROTOCOL_METHODS.contains(method)
                    ? RequestBinding.protocol(rid) : RequestBinding.other(rid);
        }
        String path = request.get("path").asString();
        if (("POST".equals(method) && ("/v1/credentials/resolve".equals(path) || "/v1/authenticator/verify".equals(path)
                || "/v1/authenticator/invalidate-codes".equals(path)))
                || ("GET".equals(method) && "/v1/authenticator".equals(path))) {
            return RequestBinding.toolScoped(rid);
        }
        if ("GET".equals(method) && ("/mcp-manifest".equals(path) || "/v1/systems".equals(path))) {
            return RequestBinding.protocol(rid);
        }
        return RequestBinding.other(rid);
    }

    static MockHttpServletRequest httpRequestFor(JsonNode request) {
        MockHttpServletRequest http;
        if ("jsonrpc".equals(request.get("kind").asString())) {
            http = new MockHttpServletRequest("POST", "/mcp");
            http.setServletPath("/mcp");
            String method = request.get("method").asString();
            String body = "tools/call".equals(method)
                    ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                            + request.get("toolName").asString() + "\",\"arguments\":{}}}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"}";
            http.setContent(body.getBytes(StandardCharsets.UTF_8));
            http.setContentType("application/json");
        } else {
            http = new MockHttpServletRequest(request.get("method").asString(), request.get("path").asString());
            http.setServletPath(request.get("path").asString());
        }
        http.addHeader("X-Request-Id", request.get("xRequestId").asString());
        return http;
    }

    static String read(String classpathResource) throws IOException {
        try (InputStream in = InternalJwtVectorsTest.class.getClassLoader().getResourceAsStream(classpathResource)) {
            assertThat(in).as(classpathResource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
