package pl.aiwcorpo.mcp.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import pl.aiwcorpo.mcp.platform.CapturedLogs;
import pl.aiwcorpo.mcp.platform.ContractSchemas;
import pl.aiwcorpo.mcp.platform.observability.AuditLogger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code audit-events} vectors of mcp-contracts 3.0.2 against the schema the starter validates its own events with,
 * and what the starter emits for every reason of {@code authentication} that concerns a receiver of the internal JWT
 * (CC-071, CC-120).
 */
class AuthenticationAuditVectorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int EXPECTED_VECTORS = 109;

    /** The reasons CC-120 gives errorCode upstream_unavailable, written out so the test does not use the starter's own predicate. */
    private static final Set<String> UPSTREAM_UNAVAILABLE = Set.of("jwks_unavailable", "replay_store_unavailable");

    record Vector(String name, JsonNode data) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> vectors() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mcp-contracts/test-vectors/audit-events/*.json");
        List<Vector> vectors = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                vectors.add(new Vector(resource.getFilename(), JSON.readTree(in)));
            }
        }
        vectors.sort(Comparator.comparing(Vector::name));
        return vectors;
    }

    static Stream<Arguments> allVectors() throws IOException {
        return vectors().stream().map(Arguments::of);
    }

    @Test
    void theWholeDirectoryIsRead() throws IOException {
        assertThat(vectors()).hasSize(EXPECTED_VECTORS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVectors")
    void theSchemaAgreesWithTheVector(Vector vector) {
        String event = JSON.writeValueAsString(vector.data().get("event"));
        boolean valid = vector.data().get("expected").get("valid").asBoolean();

        assertThat(ContractSchemas.violations(ContractSchemas.AUDIT_EVENT, event).isEmpty()).as(event).isEqualTo(valid);
    }

    @ParameterizedTest
    @EnumSource(InternalJwtRejection.class)
    void everyReasonTheStarterEmitsIsValidWithItsErrorCode(InternalJwtRejection reason) {
        Logger logger = (Logger) LoggerFactory.getLogger("test.audit.authentication.reasons");
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        try {
            new AuditLogger("test.audit.authentication.reasons", "mcp-jira").authenticationDenied(reason.code(),
                    UPSTREAM_UNAVAILABLE.contains(reason.code()) ? "upstream_unavailable" : "unauthenticated", 3);

            String line = CapturedLogs.snapshot(lines).getFirst().getFormattedMessage();
            assertThat(ContractSchemas.violations(ContractSchemas.AUDIT_EVENT, line)).as(line).isEmpty();
        } finally {
            logger.detachAppender(lines);
        }
    }

    @Test
    void theInfrastructureReasonsAreExactlyThoseWithUpstreamUnavailableInTheSchema() {
        Set<String> infrastructure = new TreeSet<>(Arrays.stream(InternalJwtRejection.values())
                .filter(InternalJwtRejection::infrastructure).map(InternalJwtRejection::code).toList());

        assertThat(infrastructure).containsExactly("jwks_unavailable", "replay_store_unavailable");
        for (String reason : infrastructure) {
            String wrong = "{\"ts\":\"2026-10-28T10:00:00.123Z\",\"requestId\":\"0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f\","
                    + "\"component\":\"server\",\"componentId\":\"mcp-jira\",\"event\":\"authentication\","
                    + "\"decision\":\"deny\",\"reason\":\"" + reason + "\",\"outcome\":\"denied\",\"errorCode\":\"unauthenticated\"}";
            assertThat(ContractSchemas.violations(ContractSchemas.AUDIT_EVENT, wrong)).as(reason).isNotEmpty();
        }
    }
}
