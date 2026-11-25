package pl.aiwcorpo.mcp.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import pl.aiwcorpo.mcp.platform.CapturedLogs;
import pl.aiwcorpo.mcp.platform.ContractSchemas;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.auth.TestCallers;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;

/** Audit lines are valid C8 events ({@code audit-event.schema.json}) and safe for line-oriented SIEMs. */
class AuditLoggerTest {

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private final AuditLogger audit;

    AuditLoggerTest() {
        Logger logger = (Logger) LoggerFactory.getLogger("test.audit.c8");
        lines.start();
        logger.addAppender(lines);
        audit = new AuditLogger("test.audit.c8", "mcp-jira",
                Clock.fixed(Instant.parse("2026-10-26T20:00:00.123Z"), ZoneOffset.UTC));
    }

    private String onlyLine() {
        assertThat(CapturedLogs.snapshot(lines)).hasSize(1);
        String line = CapturedLogs.snapshot(lines).getFirst().getFormattedMessage();
        assertThat(ContractSchemas.violations(ContractSchemas.AUDIT_EVENT, line)).as(line).isEmpty();
        return line;
    }

    @Test
    void aSuccessfulCall() {
        audit.toolCall(TestCallers.user("get_issue"), "get_issue", RiskLevel.READ_ONLY, "user-secret", "ok", null, 42, 118);

        assertThat(onlyLine()).isEqualTo("{\"ts\":\"2026-10-26T20:00:00.123Z\","
                + "\"requestId\":\"0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f\",\"component\":\"server\","
                + "\"componentId\":\"mcp-jira\",\"event\":\"tool_call\",\"sub\":\"6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11\","
                + "\"username\":\"jkowalski\",\"client\":\"claude-code\",\"server\":\"mcp-jira\",\"tool\":\"get_issue\","
                + "\"risk\":\"READ_ONLY\",\"policyRevision\":1,\"credentialType\":\"user-secret\","
                + "\"outcome\":\"ok\",\"errorCode\":null,\"latencyMs\":42,\"argsLength\":118}");
    }

    @Test
    void aRefusedCallWithoutACaller() {
        audit.toolCall(null, "get_issue", RiskLevel.READ_ONLY, null, "denied", "unauthenticated", 0, 2);

        assertThat(onlyLine()).contains("\"outcome\":\"denied\"", "\"errorCode\":\"unauthenticated\"")
                .doesNotContain("\"sub\"");
    }

    @Test
    void aCallWithUnusualCharactersInTheUsernameStaysOnOneEscapedLine() {
        CallerContext caller = new CallerContext("sub-1", "zołta\u2028k\u0007", List.of(), "claude-code", "get_issue", "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f",
                1L, 30L);

        audit.toolCall(caller, "get_issue", RiskLevel.READ_ONLY, null, "error", "internal_error", 3, 2);

        String line = onlyLine();
        assertThat(line).doesNotContain("virtualServer");
        assertThat(line.chars()).allMatch(c -> c >= 0x20 && c < 0x7f);
    }

    private static CallerContext principal(String pty, String actorSub, String actorClient) {
        return new CallerContext("6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11", "jkowalski", List.of(), "claude-code", "get_issue",
                "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", 1L, null, null, null, pty, actorSub, actorClient);
    }

    @Test
    void aHumanLeavesNoPrincipalTypeSoASiemSeesTheFieldOnlyOnceAnAgentHasCalled() {
        audit.toolCall(principal(null, null, null), "get_issue", RiskLevel.READ_ONLY, null, "ok", null, 4, 3);

        assertThat(onlyLine()).doesNotContain("principalType", "actorSub", "actorClient");
    }

    @Test
    void anAgentCallIsMarkedAndAnAutonomousOneHasNoActor() {
        audit.toolCall(principal("agent", null, null), "get_issue", RiskLevel.READ_ONLY, null, "ok", null, 4, 3);

        String line = onlyLine();
        assertThat(line).contains("\"principalType\":\"agent\"").doesNotContain("actorSub", "actorClient");
        assertThat(ContractSchemas.violations(ContractSchemas.AUDIT_EVENT, line)).as(line).isEmpty();
    }

    @Test
    void aDelegatedCallNamesTheAgentBehindTheUser() {
        audit.toolCall(principal("delegated", "3b0f6c52-8f3e-4f0e-b6a2-5d5a1f0e9c11", "agent-triage"), "get_issue",
                RiskLevel.READ_ONLY, null, "ok", null, 4, 3);

        String line = onlyLine();
        assertThat(line).contains("\"principalType\":\"delegated\"", "\"actorSub\":\"3b0f6c52-8f3e-4f0e-b6a2-5d5a1f0e9c11\"",
                "\"actorClient\":\"agent-triage\"");
        assertThat(ContractSchemas.violations(ContractSchemas.AUDIT_EVENT, line)).as(line).isEmpty();
    }

    @Test
    void thePolicyEventsOfAnAgentCarryTheSameFields() {
        audit.policyRefused(principal("agent", null, null), "get_issue", RiskLevel.READ_ONLY, "policy_data_stale", "upstream_unavailable", 2);

        assertThat(onlyLine()).contains("\"principalType\":\"agent\"");
    }
}
