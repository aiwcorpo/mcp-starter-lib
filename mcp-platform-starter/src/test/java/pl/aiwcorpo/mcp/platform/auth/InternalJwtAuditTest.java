package pl.aiwcorpo.mcp.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import pl.aiwcorpo.mcp.platform.CapturedLogs;
import pl.aiwcorpo.mcp.platform.ContractSchemas;
import pl.aiwcorpo.mcp.platform.observability.AuditLogger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A refused request leaves an {@code authentication} event (C8, CC-071) with the closed field list of 2.2.5 (CC-120). */
class InternalJwtAuditTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String GET_ISSUE =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_issue\",\"arguments\":{}}}";

    private final JsonNode vector = JSON.readTree(InternalJwtFilterTest.read("jwt/valid.json"));
    private final String token = vector.get("token").asString();
    private final Clock clock = Clock.fixed(Instant.parse(vector.get("verifier").get("now").asString()), ZoneOffset.UTC);
    private final Logger logger = (Logger) LoggerFactory.getLogger("test.audit.authentication");
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    InternalJwtAuditTest() {
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(lines);
    }

    private InternalJwtFilter filter(JtiReplayStore store, boolean mtls) {
        return filter(store, mtls, new MissingTokenEventLimiter(MissingTokenEventLimiter.DEFAULT_PER_SECOND,
                System::nanoTime, new SimpleMeterRegistry()));
    }

    private InternalJwtFilter filter(JtiReplayStore store, boolean mtls, MissingTokenEventLimiter limiter) {
        InternalJwtVerifier verifier = new InternalJwtVerifier(new InternalJwtVerifier.Settings(
                "https://mcp-gateway.internal", "mcp-jira", Duration.ofSeconds(30), Duration.ofSeconds(60), true),
                JwksGatewayKeys.of(InternalJwtFilterTest.read("keys/test-gateway-jwks.json")), store, clock);
        return new InternalJwtFilter(verifier, List.of("/actuator/health"), 1024, mtls, Set.of("mcp-gateway"),
                new AuditLogger("test.audit.authentication", "mcp-jira"), limiter);
    }

    private MockHttpServletRequest toolCall(String authorization) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.setServletPath("/mcp");
        request.setContent(GET_ISSUE.getBytes(StandardCharsets.UTF_8));
        request.setContentType("application/json");
        request.addHeader("X-Request-Id", vector.get("request").get("xRequestId").asString());
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        return request;
    }

    private JsonNode onlyEvent() {
        assertThat(CapturedLogs.snapshot(lines)).hasSize(1);
        String line = CapturedLogs.snapshot(lines).getFirst().getFormattedMessage();
        assertThat(ContractSchemas.violations(ContractSchemas.AUDIT_EVENT, line)).as(line).isEmpty();
        JsonNode event = JSON.readTree(line);
        assertThat(event.get("event").asString()).isEqualTo("authentication");
        assertThat(event.get("component").asString()).isEqualTo("server");
        assertThat(event.get("outcome").asString()).isEqualTo("denied");
        assertThat(event.get("decision").asString()).isEqualTo("deny");
        assertThat(line).doesNotContain(token).doesNotContain("\"sub\"").doesNotContain("\"tool\"")
                .doesNotContain("\"client\"").doesNotContain("\"username\"");
        return event;
    }

    @Test
    void aMissingTokenIsAudited() throws Exception {
        filter(new InMemoryJtiReplayStore(clock), false).doFilter(toolCall(null), new MockHttpServletResponse(),
                new MockFilterChain());

        JsonNode event = onlyEvent();
        assertThat(event.get("reason").asString()).isEqualTo("missing_token");
        assertThat(event.get("errorCode").asString()).isEqualTo("unauthenticated");
    }

    @Test
    void aReplayedTokenIsAuditedWithoutTheIdentityItClaims() throws Exception {
        InternalJwtFilter filter = filter(new InMemoryJtiReplayStore(clock), false);
        filter.doFilter(toolCall("Bearer " + token), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(CapturedLogs.snapshot(lines)).isEmpty();

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(toolCall("Bearer " + token), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(onlyEvent().get("reason").asString()).isEqualTo("replayed_jti");
    }

    @Test
    void aWrongClientCertificateIsAudited() throws Exception {
        filter(new InMemoryJtiReplayStore(clock), true).doFilter(toolCall("Bearer " + token),
                new MockHttpServletResponse(), new MockFilterChain());

        assertThat(onlyEvent().get("reason").asString()).isEqualTo("client_certificate_rejected");
    }

    @Test
    void aTokenThatCouldNotBeJudgedIsAuditedAsUnavailable() throws Exception {
        JtiReplayStore down = (jti, until) -> {
            throw new JtiReplayStore.StoreUnavailableException("down", null);
        };

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter(down, false).doFilter(toolCall("Bearer " + token), response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(503);
        JsonNode event = onlyEvent();
        assertThat(event.get("reason").asString()).isEqualTo("replay_store_unavailable");
        assertThat(event.get("errorCode").asString()).isEqualTo("upstream_unavailable");
    }

    @Test
    void aBrokenSignatureIsAudited() throws Exception {
        filter(new InMemoryJtiReplayStore(clock), false).doFilter(toolCall("Bearer " + token.substring(0, token.length() - 4) + "AAAA"),
                new MockHttpServletResponse(), new MockFilterChain());

        assertThat(onlyEvent().get("reason").asString()).isEqualTo("bad_signature");
    }

    @Test
    void theClientCertificateEventIsTheContractsVector() throws Exception {
        JsonNode expected = JSON.readTree(InternalJwtFilterTest.read("audit-events/server-authentication-denied-client-certificate.json"))
                .get("event");
        filter(new InMemoryJtiReplayStore(clock), true).doFilter(toolCall("Bearer " + token),
                new MockHttpServletResponse(), new MockFilterChain());

        JsonNode event = onlyEvent();
        for (String field : List.of("component", "componentId", "event", "decision", "reason", "outcome", "errorCode")) {
            assertThat(event.get(field)).as(field).isEqualTo(expected.get(field));
        }
    }

    @Test
    void aFloodOfRequestsWithoutATokenWritesOnlyTheBudgetAndCountsTheRest() throws Exception {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        InternalJwtFilter filter = filter(new InMemoryJtiReplayStore(clock), false,
                new MissingTokenEventLimiter(3, nanos::get, meters));

        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(toolCall(null), response, new MockFilterChain());
            assertThat(response.getStatus()).as("every request is still refused").isEqualTo(401);
        }

        assertThat(CapturedLogs.snapshot(lines)).hasSize(3);
        assertThat(meters.get(MissingTokenEventLimiter.METRIC).tag("reason", "missing_token").counter().count())
                .isEqualTo(7.0);

        nanos.addAndGet(1_000_000_000L);
        filter.doFilter(toolCall(null), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(CapturedLogs.snapshot(lines)).hasSize(4);
    }

    @Test
    void theCapOfMissingTokensDoesNotHideOtherRefusals() throws Exception {
        InternalJwtFilter filter = filter(new InMemoryJtiReplayStore(clock), false,
                new MissingTokenEventLimiter(1, () -> 1_000_000_000L, new SimpleMeterRegistry()));

        for (int i = 0; i < 5; i++) {
            filter.doFilter(toolCall(null), new MockHttpServletResponse(), new MockFilterChain());
        }
        String broken = "Bearer " + token.substring(0, token.length() - 4) + "AAAA";
        for (int i = 0; i < 5; i++) {
            filter.doFilter(toolCall(broken), new MockHttpServletResponse(), new MockFilterChain());
        }

        List<String> reasons = CapturedLogs.snapshot(lines).stream()
                .map(line -> JSON.readTree(line.getFormattedMessage()).get("reason").asString()).toList();
        assertThat(reasons).containsExactly("missing_token", "bad_signature", "bad_signature", "bad_signature",
                "bad_signature", "bad_signature");
    }
}
