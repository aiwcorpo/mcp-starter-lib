package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/**
 * The credential value must not appear in any log line (every logger, DEBUG included) nor in any tool response,
 * also when the backend echoes it in an error response that RestClient turns into an exception, and when tool
 * code puts it into an exception message.
 */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.protocol=STREAMABLE", "spring.ai.mcp.server.type=SYNC",
                "spring.ai.mcp.server.name=platform-leak-test",
                "spring.ai.mcp.server.streamable-http.mcp-endpoint=" + RawMcp.ENDPOINT})
@ActiveProfiles("dev")
@DisplayName("the backend credential never leaks")
class CredentialLeakTest {

    private static final String SECRET = "Zx9-jira-pat-of-jkowalski-c0ffee";
    /** Stands for system data in a backend error body: must reach neither the log nor the client. */
    private static final String BODY_MARKER = "backend-body-marker-5be1d0";
    private static final String BASIC_PAIR = "svc-jira:Wint3r-Passw0rd";
    private static final String BASIC = java.util.Base64.getEncoder()
            .encodeToString(BASIC_PAIR.getBytes(StandardCharsets.UTF_8));

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");
    static final HttpServer BACKEND = echoingBackend();

    /** Answers 401 and echoes the Authorization header in the body and in a response header, as some systems do. */
    private static HttpServer echoingBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/", exchange -> {
                String authorization = String.valueOf(exchange.getRequestHeaders().getFirst("Authorization"));
                boolean serverError = exchange.getRequestURI().getPath().contains("SRV-");
                if (exchange.getRequestURI().getPath().contains("BAD-")) {
                    // 200 with a body that does not parse: the parser's message quotes it.
                    byte[] broken = ("{\"fields\":{\"summary\":\"" + BODY_MARKER + "\"").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, broken.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(broken);
                    }
                    return;
                }
                byte[] body = ("{\"errorMessages\":[\"Invalid credentials: " + authorization + "\"],\"internal\":\""
                        + BODY_MARKER + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer error=\"invalid_token\", presented=\""
                        + authorization + "\"");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(serverError ? 500 : 401, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
        registry.add("probe.backend-url", () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        GATEWAY.close();
        BACKEND.stop(0);
    }

    @LocalServerPort
    int port;

    private final ListAppender<ILoggingEvent> everything = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private Level previous;

    @BeforeEach
    void captureAllLogs() {
        previous = root.getLevel();
        root.setLevel(Level.DEBUG);
        everything.start();
        root.addAppender(everything);
    }

    @AfterEach
    void restore() {
        root.detachAppender(everything);
        root.setLevel(previous);
    }

    private List<String> callAll(String credential) {
        return callAll(credential, "ABC-123");
    }

    private List<String> callAll(String credential, String issueKey) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port).endpoint(RawMcp.ENDPOINT).openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(user -> { }, credential))
                .build();
        List<String> responses = new ArrayList<>();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build()) {
            client.initialize();
            for (String tool : List.of("get_issue", "leaky_tool")) {
                McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder(tool)
                        .arguments("get_issue".equals(tool) ? Map.of("issueKey", issueKey) : Map.of()).build());
                assertThat(result.isError()).as(tool).isTrue();
                responses.add(GatewayTransportTest.text(result));
            }
        }
        return responses;
    }

    private List<String> logText() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : CapturedLogs.snapshot(everything)) {
            lines.add(event.getFormattedMessage());
            if (event.getThrowableProxy() != null) {
                lines.add(ThrowableProxyUtil.asString(event.getThrowableProxy()));
            }
            event.getMDCPropertyMap().values().forEach(lines::add);
        }
        return lines;
    }

    @Test
    void aBearerTokenIsInNoLogAndNoResponse() {
        List<String> responses = callAll("Bearer " + SECRET);

        assertThat(responses).allSatisfy(r -> assertThat(r).doesNotContain(SECRET));
        assertThat(responses.get(0)).contains("\"error\":\"internal_error\"");
        assertThat(responses.get(1)).contains("\"error\":\"invalid_argument\"").contains("[REDACTED]");
        List<String> logs = logText();
        assertThat(logs).as("something was logged at all").isNotEmpty();
        assertThat(logs).anySatisfy(line -> assertThat(line).contains("event=tool_failed"));
        assertThat(logs).allSatisfy(line -> assertThat(line).doesNotContain(SECRET));
    }

    @Test
    void aBasicCredentialIsInNoLogAndNoResponseEncodedOrDecoded() {
        List<String> responses = callAll("Basic " + BASIC);

        for (String text : concat(responses, logText())) {
            assertThat(text).doesNotContain(BASIC).doesNotContain(BASIC_PAIR).doesNotContain("Wint3r-Passw0rd");
        }
    }

    @Test
    void backendErrorBodiesReachNeitherTheLogNorTheClient() {
        List<String> all = new ArrayList<>(callAll("Bearer " + SECRET, "ABC-123"));
        all.addAll(callAll("Bearer " + SECRET, "SRV-1"));
        all.addAll(logText());

        assertThat(all).allSatisfy(text -> assertThat(text).doesNotContain(BODY_MARKER).doesNotContain(SECRET));
        assertThat(logText()).anySatisfy(line -> assertThat(line).contains("HTTP 500 (body not logged)"));
    }

    @Test
    void anUnparsable200BodyReachesNeitherTheLogNorTheClient() {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port).endpoint(RawMcp.ENDPOINT).openConnectionOnStartup(false)
                .httpRequestCustomizer(GATEWAY.asGateway(user -> { }, "Bearer " + SECRET))
                .build();
        List<String> all = new ArrayList<>();
        try (McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(20)).build()) {
            client.initialize();
            McpSchema.CallToolResult result = client.callTool(McpSchema.CallToolRequest.builder("get_issue_fields")
                    .arguments(Map.of("issueKey", "BAD-1")).build());
            assertThat(result.isError()).isTrue();
            all.add(GatewayTransportTest.text(result));
        }
        all.addAll(logText());

        assertThat(all).allSatisfy(text -> assertThat(text).doesNotContain(BODY_MARKER).doesNotContain(SECRET));
        assertThat(all.getFirst()).contains("\"error\":\"internal_error\"");
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }
}
