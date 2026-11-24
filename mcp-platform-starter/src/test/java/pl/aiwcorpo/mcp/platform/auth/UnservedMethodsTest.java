package pl.aiwcorpo.mcp.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import pl.aiwcorpo.mcp.platform.CapturedLogs;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The starter serves tools only (CC-259): a verified call of a resources, prompts, completion or logging method is refused
 * with -32601 before the MCP transport, an unverified one stays a 401. The tokens are the contract's vectors, presented at a
 * method they were not minted for where the claims still fit (a user token without {@code tool} and {@code resource}).
 */
class UnservedMethodsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String LIST_VECTOR = "jwt/valid-list-resources-user.json";
    private static final String READ_VECTOR = "jwt/valid-resource-read.json";

    private final JsonNode vector = JSON.readTree(InternalJwtFilterTest.read(LIST_VECTOR));

    private InternalJwtFilter filter(JsonNode source) throws Exception {
        return new InternalJwtFilter(InternalJwtVectorsTest.verifierFor(source.get("verifier")), List.of("/actuator/health"), 4096);
    }

    private MockHttpServletRequest request(JsonNode source, String body, boolean withToken) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setServletPath("/mcp");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        if (withToken) {
            request.addHeader("Authorization", "Bearer " + source.get("token").asString());
        }
        request.addHeader("X-Request-Id", source.get("request").get("xRequestId").asString());
        return request;
    }

    private static String rpc(String method, String params) {
        return "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\"" + (params == null ? "" : ",\"params\":" + params) + "}";
    }

    @ParameterizedTest
    @ValueSource(strings = {"prompts/list", "prompts/get", "completion/complete", "logging/setLevel", "resources/subscribe",
            "resources/unsubscribe"})
    void aVerifiedCallOfAMethodTheStarterDoesNotServeAnswersMethodNotFoundAndNeverReachesTheEndpoint(String method) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(vector).doFilter(request(vector, rpc(method, null), true), response, chain);

        assertThat(chain.getRequest()).as("the request must not reach the MCP endpoint").isNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(response.getContentAsString())).isEqualTo(JSON.readTree(
                "{\"jsonrpc\":\"2.0\",\"id\":7,\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}"));
    }

    @Test
    void theIdOfTheRefusedRequestIsEchoedOnlyWhenItIsAStringOrAnInteger() throws Exception {
        MockHttpServletResponse stringId = new MockHttpServletResponse();
        filter(vector).doFilter(request(vector, "{\"jsonrpc\":\"2.0\",\"id\":\"a\\\"b\",\"method\":\"prompts/list\"}", true),
                stringId, new MockFilterChain());
        assertThat(JSON.readTree(stringId.getContentAsString()).get("id").asString()).isEqualTo("a\"b");

        MockHttpServletResponse objectId = new MockHttpServletResponse();
        filter(vector).doFilter(request(vector, "{\"jsonrpc\":\"2.0\",\"id\":{\"x\":1},\"method\":\"prompts/list\"}", true),
                objectId, new MockFilterChain());
        assertThat(JSON.readTree(objectId.getContentAsString()).get("id").isNull()).isTrue();
    }

    @Test
    void withoutAValidTokenTheMethodIsNotAnswered() throws Exception {
        MockHttpServletResponse noToken = new MockHttpServletResponse();
        filter(vector).doFilter(request(vector, rpc("prompts/get", null), false), noToken, new MockFilterChain());
        assertThat(noToken.getStatus()).isEqualTo(401);
        assertThat(noToken.getContentAsString()).doesNotContain("-32601");

        JsonNode toolCall = JSON.readTree(InternalJwtFilterTest.read("jwt/valid.json"));
        MockHttpServletResponse toolToken = new MockHttpServletResponse();
        filter(toolCall).doFilter(request(toolCall, rpc("prompts/get", "{\"name\":\"x\"}"), true), toolToken, new MockFilterChain());
        assertThat(toolToken.getStatus()).as("a token minted for a tool is not valid at another method").isEqualTo(401);
    }

    @Test
    void aResourceReadIsRefusedOnlyAfterTheTokenIsBoundToTheUri() throws Exception {
        JsonNode read = JSON.readTree(InternalJwtFilterTest.read(READ_VECTOR));
        String uri = read.get("request").get("uri").asString();

        MockHttpServletResponse bound = new MockHttpServletResponse();
        MockFilterChain boundChain = new MockFilterChain();
        filter(read).doFilter(request(read, rpc("resources/read", "{\"uri\":\"" + uri + "\"}"), true), bound, boundChain);
        assertThat(bound.getContentAsString()).contains("\"code\":-32601");
        assertThat(boundChain.getRequest()).isNull();

        MockHttpServletResponse other = new MockHttpServletResponse();
        filter(read).doFilter(request(read, rpc("resources/read", "{\"uri\":\"" + uri + "9\"}"), true), other, new MockFilterChain());
        assertThat(other.getStatus()).isEqualTo(401);

        MockHttpServletResponse noUri = new MockHttpServletResponse();
        filter(read).doFilter(request(read, rpc("resources/read", "{}"), true), noUri, new MockFilterChain());
        assertThat(noUri.getStatus()).as("no params.uri equals no resource").isEqualTo(401);
    }

    @ParameterizedTest
    @ValueSource(strings = {"prompt/get", "completions/complete", "resource/read", "log/setLevel", "Prompts/list"})
    void aMethodThatOnlyLooksLikeAFamilyIsLeftToTheTransport(String method) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter(vector).doFilter(request(vector, rpc(method, null), true), response, chain);

        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void theRefusalLogsTheFamilyNotTheMethodTheCallerWrote() throws Exception {
        Logger log = (Logger) LoggerFactory.getLogger(InternalJwtFilter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        log.addAppender(appender);
        try {
            filter(vector).doFilter(request(vector, rpc("prompts/get-with-a-long-tail\\u0001\\\"", null), true),
                    new MockHttpServletResponse(), new MockFilterChain());

            List<String> lines = CapturedLogs.snapshot(appender).stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("mcp_method_refused")).toList();
            assertThat(lines).containsExactly("event=mcp_method_refused method=prompts/* reason=not_served");
            assertThat(String.join("", lines)).doesNotContain(vector.get("token").asString());
        } finally {
            log.detachAppender(appender);
        }
    }
}
