package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.listbean.ListBeanServer;

/**
 * The server serves tools only (CC-259): it declares no resources, prompts, completions or logging, the gateway's resource and
 * prompt methods are refused with -32601 even with a valid token, and a server that tries to add one of them does not start.
 */
@SpringBootTest(classes = ListBeanServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.name=platform-unserved-primitives"})
@ActiveProfiles("dev")
@DisplayName("a server that serves tools only")
class UnservedPrimitivesTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");

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

    @Test
    @DisplayName("initialize declares tools and nothing else")
    void initializeDeclaresToolsAndNothingElse() throws Exception {
        HttpResponse<String> initialize = new RawMcp(port).post("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                "capabilities":{},"clientInfo":{"name":"platform-test","version":"1"}}}""",
                GATEWAY.user().mint().headers(), null);

        assertThat(initialize.statusCode()).isEqualTo(200);
        assertThat(initialize.body()).contains("\"tools\"")
                .doesNotContain("\"resources\"", "\"prompts\"", "\"completions\"", "\"logging\"");
    }

    @Test
    @DisplayName("resources, prompts, completion and logging methods are answered with -32601 and no handler runs")
    void theMethodsOfTheGatewayAreRefusedEvenWithAValidToken() throws Exception {
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);
        String uri = "servicedesk://ticket/SD-1042";
        Map<String, String> requests = Map.of(
                "resources/list", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}",
                "resources/templates/list", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/templates/list\"}",
                "prompts/list", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/list\"}",
                "prompts/get", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"prompts/get\",\"params\":{\"name\":\"p\"}}",
                "completion/complete", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"completion/complete\",\"params\":{}}",
                "logging/setLevel", "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"logging/setLevel\",\"params\":{\"level\":\"debug\"}}");
        for (Map.Entry<String, String> request : requests.entrySet()) {
            HttpResponse<String> answer = raw.post(request.getValue(), GATEWAY.user().mint().headers(), session);

            assertThat(answer.body()).as(request.getKey()).contains("\"code\":-32601").doesNotContain("\"result\"");
        }

        HttpResponse<String> read = raw.post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/read\",\"params\":{\"uri\":\"" + uri + "\"}}",
                GATEWAY.user().extraClaim("resource", uri).mint().headers(), session);
        assertThat(read.body()).contains("\"code\":-32601");
    }

    @Test
    @DisplayName("a resources/read whose token is for another URI is a 401, not a method refusal")
    void aReadWithATokenForAnotherUriIsNotAuthenticated() throws Exception {
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);

        HttpResponse<String> read = raw.post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/read\",\"params\":{\"uri\":\"servicedesk://ticket/SD-1\"}}",
                GATEWAY.user().extraClaim("resource", "servicedesk://ticket/SD-2").mint().headers(), session);

        assertThat(read.statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("an agent token (pty) is accepted for a tool call like a human one")
    void anAgentTokenReachesTheToolLikeAHumanOne() throws Exception {
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);

        HttpResponse<String> call = raw.post(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"echo_text\",\"arguments\":{\"text\":\"a\"}}}",
                GATEWAY.user().tool("echo_text").extraClaim("pty", "agent").mint().headers(), session);

        assertThat(call.statusCode()).isEqualTo(200);
        assertThat(call.body()).contains("\"result\"");
    }

    @Configuration(proxyBeanMethods = false)
    static class ResourceBean {

        @Bean
        McpServerFeatures.SyncResourceSpecification resource() {
            return new McpServerFeatures.SyncResourceSpecification(
                    McpSchema.Resource.builder().uri("jira://issue/ABC-1").name("issue").build(), (exchange, request) -> null);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class PromptListBean {

        @Bean
        List<McpServerFeatures.SyncPromptSpecification> prompts() {
            return List.of(new McpServerFeatures.SyncPromptSpecification(
                    new McpSchema.Prompt("triage", "Triage a ticket", List.of()), (exchange, request) -> null));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CompletionBean {

        @Bean
        McpServerFeatures.SyncCompletionSpecification completion() {
            return new McpServerFeatures.SyncCompletionSpecification(new McpSchema.PromptReference("triage"), (exchange, request) -> null);
        }
    }

    private static Throwable startRefused(Map<String, Object> extra, Class<?>... additional) {
        Map<String, Object> properties = new HashMap<>(GATEWAY.serverProperties());
        properties.put("spring.ai.mcp.server.name", "platform-unserved-start");
        properties.putAll(extra);
        Class<?>[] sources = new Class<?>[additional.length + 1];
        sources[0] = ListBeanServer.class;
        System.arraycopy(additional, 0, sources, 1, additional.length);
        return catchThrowable(() -> SpringTestContextStart.start(sources, properties, "dev").close());
    }

    @Test
    @DisplayName("a resource specification bean stops the start")
    void aResourceSpecificationBeanStopsTheStart() {
        assertThat(startRefused(Map.of(), ResourceBean.class)).rootCause().hasMessageContaining("not tools only")
                .hasMessageContaining("SyncResourceSpecification");
    }

    @Test
    @DisplayName("a list of prompt specifications stops the start, whatever the capability property says")
    void aListOfPromptSpecificationsStopsTheStart() {
        assertThat(startRefused(Map.of("spring.ai.mcp.server.capabilities.prompt", "false"), PromptListBean.class))
                .rootCause().hasMessageContaining("not tools only").hasMessageContaining("SyncPromptSpecification");
    }

    @Test
    @DisplayName("a completion specification bean stops the start")
    void aCompletionSpecificationBeanStopsTheStart() {
        assertThat(startRefused(Map.of(), CompletionBean.class)).rootCause().hasMessageContaining("SyncCompletionSpecification");
    }

    @Test
    @DisplayName("switching a capability on with the Spring AI property stops the start")
    void switchingACapabilityOnStopsTheStart() {
        for (String capability : List.of("resource", "prompt", "completion")) {
            assertThat(startRefused(Map.of("spring.ai.mcp.server.capabilities." + capability, "true")))
                    .as(capability).rootCause().hasMessageContaining("not tools only").hasMessageContaining("declared");
        }
    }

    @Test
    @DisplayName("without any of that the server starts")
    void aToolsOnlyServerStarts() {
        assertThat(startRefused(Map.of())).isNull();
    }
}
