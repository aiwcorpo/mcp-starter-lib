package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.NoopTracerAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import pl.aiwcorpo.mcp.platform.observability.PlatformObservabilityAutoConfiguration;

/**
 * A server that calls its backend with a {@code RestClient} that has the observation registry (Spring Boot's builder sets it) gets a client span
 * for the call. What the exporter
 * receives must hold neither the query of the URL (it carries tool arguments) nor anything the backend put in an error response.
 */
class OutboundCallRedactionTest {

    private static final String BODY_MARKER = "BACKEND-BODY-MARKER-5e0a";
    private static final String QUERY_MARKER = "QUERY-MARKER-3b7d";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
            ObservationAutoConfiguration.class, MicrometerTracingAutoConfiguration.class, NoopTracerAutoConfiguration.class,
            OpenTelemetrySdkAutoConfiguration.class, OpenTelemetryTracingAutoConfiguration.class, OtlpTracingAutoConfiguration.class,
            PlatformObservabilityAutoConfiguration.class));

    private TraceCollectorStub collector;
    private HttpServer backend;

    @BeforeEach
    void start() throws Exception {
        collector = new TraceCollectorStub();
        backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                int status = path.startsWith("/client-error") ? 400 : path.startsWith("/server-error") ? 502 : 200;
                byte[] body = ("{\"message\":\"" + BODY_MARKER + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        backend.start();
    }

    @AfterEach
    void stop() {
        backend.stop(0);
        collector.close();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + backend.getAddress().getPort();
    }

    private String exportedAfter(java.util.function.Consumer<RestClient> call) {
        runner.withPropertyValues("management.tracing.export.enabled=true", "management.opentelemetry.enabled=true",
                "management.tracing.sampling.probability=1.0", "management.opentelemetry.tracing.export.schedule-delay=100ms",
                "management.opentelemetry.tracing.export.otlp.endpoint=" + collector.endpoint(), "mcp.platform.server-id=mcp-probe")
                .run(context -> {
                    RestClient client = RestClient.builder().observationRegistry(context.getBean(ObservationRegistry.class)).build();
                    io.micrometer.tracing.Span parent = context.getBean(Tracer.class).nextSpan().name("tool-call").start();
                    try (Tracer.SpanInScope ignored = context.getBean(Tracer.class).withSpan(parent)) {
                        call.accept(client);
                    } finally {
                        parent.end();
                    }
                    await().atMost(Duration.ofSeconds(10)).until(() -> collector.exported().length > 0);
                });
        return new String(collector.exported(), StandardCharsets.ISO_8859_1);
    }

    @Test
    void aConcatenatedQueryStringDoesNotReachTheCollector() {
        String exported = exportedAfter(client -> client.get().uri(baseUrl() + "/search?q=" + QUERY_MARKER).retrieve().toBodilessEntity());
        assertThat(exported).contains("tool-call").doesNotContain(QUERY_MARKER).doesNotContain("search?q=");
    }

    @Test
    void aClientErrorKeepsTheStatusAndNeitherTheBodyNorTheQuery() {
        String exported = exportedAfter(client -> assertThatThrownBy(
                () -> client.get().uri(baseUrl() + "/client-error?issue=" + QUERY_MARKER).retrieve().body(String.class))
                .isInstanceOf(RestClientResponseException.class));
        assertThat(exported).contains("400").contains("HttpClientErrorException")
                .doesNotContain(BODY_MARKER).doesNotContain(QUERY_MARKER);
    }

    @Test
    void aServerErrorKeepsTheStatusAndNeitherTheBodyNorTheQuery() {
        String exported = exportedAfter(client -> assertThatThrownBy(
                () -> client.get().uri(baseUrl() + "/server-error?issue=" + QUERY_MARKER).retrieve().body(String.class))
                .isInstanceOf(RestClientResponseException.class));
        assertThat(exported).contains("502").contains("HttpServerErrorException").doesNotContain(BODY_MARKER).doesNotContain(QUERY_MARKER);
    }

    @Test
    void aTemplateWithVariablesKeepsItsPathAsTheUriTag() {
        String exported = exportedAfter(client -> client.get().uri(baseUrl() + "/items/{id}?q={q}", "42", QUERY_MARKER).retrieve()
                .toBodilessEntity());
        assertThat(exported).contains("/items/{id}").doesNotContain(QUERY_MARKER).doesNotContain("q={q}");
    }
}
