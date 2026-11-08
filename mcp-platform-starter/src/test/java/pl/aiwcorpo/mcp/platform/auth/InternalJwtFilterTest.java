package pl.aiwcorpo.mcp.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The filter around the verifier: what it reads from the request and how it refuses. Uses the contract's
 * {@code valid.json} vector (a {@code tools/call} of {@code get_issue}) with the vector's clock.
 */
class InternalJwtFilterTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JsonNode vector = JSON.readTree(read("jwt/valid.json"));
    private final String token = vector.get("token").asString();
    private final String rid = vector.get("request").get("xRequestId").asString();
    private final Clock clock = Clock.fixed(Instant.parse(vector.get("verifier").get("now").asString()), ZoneOffset.UTC);

    private InternalJwtFilter filter(JtiReplayStore store, GatewayKeys keys) {
        InternalJwtVerifier verifier = new InternalJwtVerifier(new InternalJwtVerifier.Settings(
                "https://mcp-gateway.internal", "mcp-jira", Duration.ofSeconds(30), Duration.ofSeconds(60), true),
                keys, store, clock);
        return new InternalJwtFilter(verifier, List.of("/actuator/health", "/actuator/health/liveness"), 1024);
    }

    private InternalJwtFilter filter() {
        return filter(new InMemoryJtiReplayStore(clock), JwksGatewayKeys.of(read("keys/test-gateway-jwks.json")));
    }

    private MockHttpServletRequest toolCall(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setServletPath("/mcp");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("X-Request-Id", rid);
        return request;
    }

    private static final String GET_ISSUE =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_issue\",\"arguments\":{}}}";

    @Test
    void aValidCallReachesTheEndpointWithTheCallerBoundAndTheSameBody() throws Exception {
        AtomicReference<CallerContext> seen = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res)
                    throws java.io.IOException {
                seen.set(CallerContext.current().orElse(null));
                body.set(new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
        });

        filter().doFilter(toolCall(GET_ISSUE), new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isNotNull();
        assertThat(seen.get().tool()).isEqualTo("get_issue");
        assertThat(seen.get().username()).isEqualTo("jkowalski");
        assertThat(body.get()).isEqualTo(GET_ISSUE);
        assertThat(CallerContext.current()).as("cleared after the request").isEmpty();
    }

    @Test
    void aBatchIsRefused() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(toolCall("[" + GET_ISSUE + "]"), response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void duplicateKeysCannotSmuggleAnotherToolName() throws Exception {
        String smuggled = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"get_issue\",\"name\":\"delete_issue\",\"arguments\":{}}}";
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(toolCall(smuggled), response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void anOversizedBodyIsRefusedBeforeVerification() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter().doFilter(toolCall("{\"pad\":\"" + "x".repeat(2000) + "\"}"), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void twoAuthorizationHeadersAreRefused() throws Exception {
        MockHttpServletRequest request = toolCall(GET_ISSUE);
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void anUnreachableJtiStoreRefusesWith503() throws Exception {
        JtiReplayStore down = (jti, until) -> {
            throw new JtiReplayStore.StoreUnavailableException("down", null);
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(down, JwksGatewayKeys.of(read("keys/test-gateway-jwks.json")))
                .doFilter(toolCall(GET_ISSUE), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("upstream_unavailable");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void anUnreachableJwksRefusesWith503() throws Exception {
        GatewayKeys down = new JwksGatewayKeys(() -> {
            throw new java.io.IOException("connection refused");
        }, clock, Duration.ofMinutes(10), Duration.ofHours(1), Duration.ofSeconds(30));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter(new InMemoryJtiReplayStore(clock), down).doFilter(toolCall(GET_ISSUE), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void onlyExactHealthPathsAreExempt() throws Exception {
        for (String[] uriAndServletPath : new String[][] {
                {"/actuator/health", "/actuator/health"},
                {"/actuator/health/liveness", "/actuator/health/liveness"}}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uriAndServletPath[0]);
            request.setServletPath(uriAndServletPath[1]);
            MockFilterChain chain = new MockFilterChain();
            filter().doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.getRequest()).as(uriAndServletPath[0]).isNotNull();
        }
        // Raw URIs the container may normalize to another path are never exempt, whatever they resolve to.
        for (String[] uriAndServletPath : new String[][] {
                {"/actuator/health/..;/mcp", "/mcp"},
                {"/actuator/health;x", "/actuator/health"},
                {"/actuator/%68ealth", "/actuator/health"},
                {"//actuator/health", "/actuator/health"},
                {"/actuator/health/", "/actuator/health/"},
                {"/actuator/health/other", "/actuator/health/other"},
                {"/ACTUATOR/health", "/ACTUATOR/health"}}) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uriAndServletPath[0]);
            request.setServletPath(uriAndServletPath[1]);
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter().doFilter(request, response, chain);
            assertThat(chain.getRequest()).as(uriAndServletPath[0]).isNull();
            assertThat(response.getStatus()).as(uriAndServletPath[0]).isEqualTo(401);
        }
    }

    @Test
    void patternsAreNotAcceptedAsUnauthenticatedPaths() {
        assertThatThrownBy(() -> new InternalJwtFilter(null, List.of("/actuator/**"), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static String read(String path) {
        try {
            return InternalJwtVectorsTest.read("mcp-contracts/test-vectors/" + path);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
