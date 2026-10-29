package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.platform.auth.InternalJwtRejection;
import pl.aiwcorpo.mcp.platform.auth.MissingTokenEventLimiter;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/**
 * The cap on {@code missing_token} audit events over the real transport: the property sets the budget, every request
 * is still answered 401, the surplus shows up in the starter's metric on the application's own registry.
 */
@SpringBootTest(classes = ProbeServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.ai.mcp.server.name=missing-token-cap-test",
                "aiwcorpo.mcp.platform.internal-jwt.missing-token-events-per-second=2",
                "aiwcorpo.mcp.platform.internal-jwt.max-request-body=2KB"})
@ActiveProfiles("dev")
class MissingTokenAuditCapTest {

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
    MeterRegistry meters;

    private final Logger audit = (Logger) LoggerFactory.getLogger("pl.aiwcorpo.mcp.audit");
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    @AfterEach
    void detach() {
        audit.detachAppender(lines);
    }

    @Test
    void aFloodWithoutATokenIsRefusedEveryTimeAndWrittenWithinTheBudget() throws Exception {
        lines.start();
        audit.addAppender(lines);
        int requests = 12;
        double before = suppressed();
        long started = System.nanoTime();

        for (int i = 0; i < requests; i++) {
            assertThat(new RawMcp(port).post(RawMcp.toolCall("probe_credential"), Map.of(), null).statusCode())
                    .isEqualTo(401);
        }
        long seconds = (System.nanoTime() - started) / 1_000_000_000L + 1;

        long written = CapturedLogs.snapshot(lines).stream()
                .filter(line -> line.getFormattedMessage().contains("\"event\":\"authentication\""))
                .filter(line -> line.getFormattedMessage().contains("\"reason\":\"missing_token\"")).count();
        assertThat(written).isBetween(2L, 2L * (seconds + 1));
        assertThat(written + (suppressed() - before)).isEqualTo(requests);
    }

    @Test
    void aRequestRefusedWith400Or413BeforeTheJwtIsNotAnAuthenticationEventAndNotCounted() throws Exception {
        lines.start();
        audit.addAppender(lines);
        double before = suppressed();
        Map<String, String> headers = Map.of("Authorization", "Bearer a.b.c");

        assertThat(new RawMcp(port).post("[{\"jsonrpc\":\"2.0\"}]", headers, null).statusCode()).as("a batch is not one object").isEqualTo(400);
        assertThat(new RawMcp(port).post("{\"jsonrpc\":\"2.0\",\"method\":\"ping\",\"pad\":\"" + "x".repeat(4096) + "\"}", headers, null)
                .statusCode()).as("the body is over the limit").isEqualTo(413);

        assertThat(CapturedLogs.snapshot(lines).stream().filter(line -> line.getFormattedMessage().contains("\"event\":\"authentication\"")))
                .as("no authentication event for a request that never reached the verification").isEmpty();
        assertThat(suppressed()).isEqualTo(before);
    }

    @Test
    void theSurplusCounterHasTheNameAndTheTagsOfTheContract() {
        PrometheusMeterRegistry scrape = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        MissingTokenEventLimiter limiter = new MissingTokenEventLimiter(1, () -> 0L, scrape);
        limiter.tryAcquire(InternalJwtRejection.MALFORMED);
        limiter.tryAcquire(InternalJwtRejection.MALFORMED);

        assertThat(scrape.scrape()).contains("mcp_audit_authentication_suppressed_total{component=\"server\",reason=\"malformed\"} 1.0");
        assertThat(MissingTokenEventLimiter.METRIC).isEqualTo("mcp_audit_authentication_suppressed_total");
    }

    private double suppressed() {
        var counter = meters.find(MissingTokenEventLimiter.METRIC).tag("component", "server").tag("reason", "missing_token").counter();
        return counter == null ? 0 : counter.count();
    }
}
