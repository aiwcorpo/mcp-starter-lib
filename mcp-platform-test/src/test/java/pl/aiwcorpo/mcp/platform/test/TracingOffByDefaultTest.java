package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/** A server that sets nothing about tracing starts no OpenTelemetry SDK and still answers a request that carries a traceparent. */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class TracingOffByDefaultTest {

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

    @Autowired
    ApplicationContext context;

    @Test
    void noSdkIsStartedAndARequestWithATraceparentIsServedNormally() throws Exception {
        assertThat(context.getBeanNamesForType(SdkTracerProvider.class)).isEmpty();
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);
        Map<String, String> headers = new LinkedHashMap<>(GATEWAY.user().tool("probe_caller").mint().headers());
        headers.put("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

        HttpResponse<String> call = raw.post(RawMcp.toolCall("probe_caller"), headers, session);

        assertThat(call.statusCode()).isEqualTo(200);
    }
}
