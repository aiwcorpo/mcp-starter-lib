package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/**
 * A server that sets MCP_OTLP_ENDPOINT continues the trace of the gateway's traceparent and exports spans that name no token, tool or
 * argument. The variable is set as a system property before the context starts, because the collector's port is not known earlier.
 */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.sampling.probability=1.0")
@ActiveProfiles("dev")
class TracingTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");
    static TraceCollectorStub collector;

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    private static final String PARENT_SPAN_ID = "00f067aa0ba902b7";

    @BeforeAll
    static void collector() throws Exception {
        collector = new TraceCollectorStub();
        System.setProperty("MCP_OTLP_ENDPOINT", collector.endpoint());
    }

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
        registry.add("management.opentelemetry.tracing.export.schedule-delay", () -> "200ms");
    }

    @AfterAll
    static void stop() {
        System.clearProperty("MCP_OTLP_ENDPOINT");
        collector.close();
        GATEWAY.close();
    }

    @LocalServerPort
    int port;

    @Test
    void theTraceOfTheGatewaysCallContinuesAndItsSpanIsExportedWithoutTokenOrToolName() throws Exception {
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);
        Map<String, String> headers = new LinkedHashMap<>(GATEWAY.user().tool("probe_caller").mint().headers());
        headers.put("traceparent", "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01");

        HttpResponse<String> call = raw.post(RawMcp.toolCall("probe_caller"), headers, session);

        assertThat(call.statusCode()).isEqualTo(200);
        byte[] traceId = HexFormat.of().parseHex(TRACE_ID);
        byte[] parentSpanId = HexFormat.of().parseHex(PARENT_SPAN_ID);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(contains(collector.exported(), traceId)).as("spans of the gateway's trace").isTrue();
            assertThat(contains(collector.exported(), parentSpanId)).as("the gateway's span is the parent").isTrue();
        });
        String text = new String(collector.exported(), StandardCharsets.ISO_8859_1);
        assertThat(text).contains("mcp-").doesNotContain("probe_caller").doesNotContain("Bearer").doesNotContain("jkowalski")
                .doesNotContain(headers.get("Authorization") == null ? "no-authorization-header" : headers.get("Authorization"));
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
