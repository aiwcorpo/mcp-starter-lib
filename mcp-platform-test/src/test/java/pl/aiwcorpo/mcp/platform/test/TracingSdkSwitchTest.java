package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/**
 * A server that sets MCP_OTLP_ENDPOINT and management.opentelemetry.enabled=false (MANAGEMENT_OPENTELEMETRY_ENABLED=false) starts no SDK and exports nothing. The
 * variable is set as a system property before the context starts, because the collector's port is not known earlier.
 */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.tracing.sampling.probability=1.0", "management.opentelemetry.enabled=false"})
@ActiveProfiles("dev")
class TracingSdkSwitchTest {

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

    @Autowired
    ApplicationContext context;

    @Test
    void theSdkSwitchKeepsSpansAwayFromTheCollectorEvenWithAnEndpoint() throws Exception {
        assertThat(context.getBeanNamesForType(SdkTracerProvider.class)).isEmpty();
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);
        Map<String, String> headers = new LinkedHashMap<>(GATEWAY.user().tool("probe_caller").mint().headers());
        headers.put("traceparent", "00-" + TRACE_ID + "-" + PARENT_SPAN_ID + "-01");

        HttpResponse<String> call = raw.post(RawMcp.toolCall("probe_caller"), headers, session);

        assertThat(call.statusCode()).isEqualTo(200);
        Thread.sleep(1000);
        assertThat(collector.exported()).isEmpty();
    }
}
