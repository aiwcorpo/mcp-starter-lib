package pl.aiwcorpo.mcp.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** The sidecar mode for the classification of an adapter (CC-226), beyond what the contract vectors hold. */
class AdapterReceiverModeTest {

    private static final String RID = "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f";
    private static final Instant NOW = Instant.parse("2026-10-26T12:00:10Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private InternalJwtVerifier verifier(ReceiverMode mode) throws Exception {
        String jwks = InternalJwtVectorsTest.read("mcp-contracts/test-vectors/keys/test-gateway-jwks.json");
        return new InternalJwtVerifier(new InternalJwtVerifier.Settings("https://mcp-gateway.internal", "mcp-jira",
                Duration.ofSeconds(30), Duration.ofSeconds(60), true, false, mode),
                JwksGatewayKeys.of(jwks), new InMemoryJtiReplayStore(CLOCK), CLOCK, meters);
    }

    private static String token(Map<String, Object> extra) throws Exception {
        ECKey key = ECKey.parse(InternalJwtVectorsTest.read(
                "mcp-contracts/test-vectors/keys/test-gateway-jwt-a.NOT-FOR-PRODUCTION.private.jwk.json"));
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer("https://mcp-gateway.internal").audience("mcp-jira")
                .subject("6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11").claim("usr", "jkowalski")
                .claim("grp", List.of("mcp-jira-read")).claim("azp", "claude-code").claim("tool", "get_issue")
                .claim("use", "call").claim("rid", RID).claim("prv", 1L)
                .issueTime(Date.from(NOW.minusSeconds(10))).expirationTime(Date.from(NOW.plusSeconds(50)))
                .jwtID(UUID.randomUUID().toString());
        extra.forEach(claims::claim);
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).type(
                com.nimbusds.jose.JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new ECDSASigner(key));
        return jwt.serialize();
    }

    @Test
    void theAdapterTakesTheClassificationIntoTheCaller() throws Exception {
        CallerContext caller = verifier(ReceiverMode.ADAPTER).verify(
                token(Map.of("mcp_risk", "READ_ONLY", "mcp_action", "read")), RequestBinding.toolCall("get_issue", RID));

        assertThat(caller.mcpRisk()).isEqualTo("READ_ONLY");
        assertThat(caller.mcpAction()).isEqualTo("read");
        assertThat(meters.getMeters()).isEmpty();
    }

    @Test
    void aRiskOtherThanReadOnlyIsLoggedAndCountedButAccepted() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(InternalJwtVerifier.class);
        ListAppender<ILoggingEvent> lines = new ListAppender<>();
        lines.start();
        logger.addAppender(lines);
        try {
            CallerContext caller = verifier(ReceiverMode.ADAPTER).verify(
                    token(Map.of("mcp_risk", "UPDATE", "mcp_action", "update")), RequestBinding.toolCall("get_issue", RID));

            assertThat(caller.mcpRisk()).isEqualTo("UPDATE");
            assertThat(meters.get("mcp.adapter.classification.not_read_only").tag("risk", "UPDATE").counter().count())
                    .isEqualTo(1.0);
            assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage)
                    .anyMatch(line -> line.contains("adapter_classification_not_read_only") && line.contains("risk=UPDATE"));
        } finally {
            logger.detachAppender(lines);
        }
    }

    @Test
    void aNativeServerRejectsTheClassificationClaimsAndTheAdapterRejectsTheirAbsenceAtToolCall() throws Exception {
        String classified = token(Map.of("mcp_risk", "READ_ONLY", "mcp_action", "read"));
        String plain = token(Map.of());

        assertThatThrownBy(() -> verifier(ReceiverMode.NATIVE).verify(classified, RequestBinding.toolCall("get_issue", RID)))
                .isInstanceOf(InternalJwtRejectedException.class).extracting(e -> ((InternalJwtRejectedException) e).reason())
                .isEqualTo(InternalJwtRejection.INVALID_CLAIMS);
        assertThatThrownBy(() -> verifier(ReceiverMode.ADAPTER).verify(plain, RequestBinding.toolCall("get_issue", RID)))
                .isInstanceOf(InternalJwtRejectedException.class).extracting(e -> ((InternalJwtRejectedException) e).reason())
                .isEqualTo(InternalJwtRejection.CLASSIFICATION_MISSING);
        assertThat(verifier(ReceiverMode.NATIVE).verify(plain, RequestBinding.toolCall("get_issue", RID)).mcpRisk()).isNull();
    }
}
