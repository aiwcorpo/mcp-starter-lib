package pl.aiwcorpo.mcp.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import pl.aiwcorpo.mcp.platform.CapturedLogs;
import pl.aiwcorpo.mcp.platform.ContractSchemas;
import pl.aiwcorpo.mcp.platform.auth.TestCallers;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;
import pl.aiwcorpo.mcp.platform.guard.RiskClassifiedTool;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.input.ToolInputPolicy;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.security.CredentialRequiredException;

class AuditingToolCallbackTest {

    private final ListAppender<ILoggingEvent> auditLines = new ListAppender<>();
    private final AuditLogger audit = new AuditLogger("test.audit.callback", "mcp-jira");
    private final McpErrorMapper errors = new McpErrorMapper(List.of(), "http://localhost:18090/connections?system={system}");

    AuditingToolCallbackTest() {
        auditLines.start();
        ((Logger) LoggerFactory.getLogger("test.audit.callback")).addAppender(auditLines);
    }

    private record Tool(String name, Supplier<String> behaviour) implements ToolCallback, RiskClassifiedTool {

        @Override
        public String title() {
            return "Test tool";
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name(name).description("test").inputSchema("{\"type\":\"object\",\"properties\":{\"issueKey\":{\"type\":\"string\",\"maxLength\":64}},\"additionalProperties\":false}").build();
        }

        @Override
        public String call(String toolInput) {
            return behaviour.get();
        }

        @Override
        public RiskLevel riskLevel() {
            return RiskLevel.READ_ONLY;
        }
    }

    private ToolCallback decorate(Supplier<String> behaviour, ToolCallInterceptor... interceptors) {
        return new AuditingToolCallback(new Tool("get_issue", behaviour), audit, errors,
                new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL), List.of(interceptors));
    }

    @Test
    void aResultOverTheLimitIsInternalErrorWithoutItsContent() {
        ToolCallback callback = new AuditingToolCallback(new Tool("get_issue", () -> "{\"text\":\"" + "x".repeat(200) + "\"}"),
                audit, errors, new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL), List.of(), ToolInputPolicy.STRICT, 100);

        assertThatThrownBy(() -> TestCallers.as(TestCallers.user("get_issue"), () -> callback.call("{}")))
                .isInstanceOf(SanitizedToolException.class).hasMessageContaining("\"error\":\"internal_error\"")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("xxxx"));
        assertThat(lastAudit()).contains("\"outcome\":\"error\"");
    }

    @Test
    void theLimitCountsBytesNotCharacters() {
        String multibyte = "{\"t\":\"" + "\u00f3".repeat(30) + "\"}";
        ToolCallback atLimit = new AuditingToolCallback(new Tool("get_issue", () -> multibyte), audit, errors,
                new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL), List.of(), ToolInputPolicy.STRICT, 68);
        ToolCallback over = new AuditingToolCallback(new Tool("get_issue", () -> multibyte), audit, errors,
                new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL), List.of(), ToolInputPolicy.STRICT, 67);

        assertThat(TestCallers.as(TestCallers.user("get_issue"), () -> atLimit.call("{}"))).isEqualTo(multibyte);
        assertThatThrownBy(() -> TestCallers.as(TestCallers.user("get_issue"), () -> over.call("{}")))
                .hasMessageContaining("internal_error");
    }

    private String lastAudit() {
        String line = CapturedLogs.snapshot(auditLines).getLast().getFormattedMessage();
        assertThat(ContractSchemas.violations(ContractSchemas.AUDIT_EVENT, line)).as(line).isEmpty();
        return line;
    }

    @Test
    void aBoundCallRunsAndIsAudited() {
        ToolCallback callback = decorate(() -> "{\"key\":\"ABC-1\"}");

        String result = TestCallers.as(TestCallers.user("get_issue"), () -> callback.call("{\"issueKey\":\"ABC-1\"}"));

        assertThat(result).isEqualTo("{\"key\":\"ABC-1\"}");
        assertThat(lastAudit()).contains("\"outcome\":\"ok\"", "\"argsLength\":20", "\"risk\":\"READ_ONLY\"")
                .doesNotContain("ABC-1");
    }

    @Test
    void withoutACallerTheToolDoesNotRun() {
        ToolCallback callback = decorate(() -> {
            throw new AssertionError("must not run");
        });

        assertThatThrownBy(() -> callback.call("{}")).isInstanceOf(SanitizedToolException.class)
                .hasMessageContaining("\"error\":\"unauthenticated\"");
        assertThat(lastAudit()).contains("\"outcome\":\"denied\"");
    }

    @Test
    void aTokenForAnotherToolOrTheServiceVariantDoesNotRunIt() {
        ToolCallback callback = decorate(() -> {
            throw new AssertionError("must not run");
        });

        assertThatThrownBy(() -> TestCallers.as(TestCallers.user("delete_issue"), () -> callback.call("{}")))
                .hasMessageContaining("unauthenticated");
        assertThatThrownBy(() -> TestCallers.as(TestCallers.service(), () -> callback.call("{}")))
                .hasMessageContaining("unauthenticated");
    }

    @Test
    void backendErrorsLeaveAsSafeC10Json() {
        ToolCallback callback = decorate(() -> {
            throw HttpClientErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                    null, "{\"errors\":\"auth failed at https://jira.internal:8443\"}".getBytes(), null);
        });

        assertThatThrownBy(() -> TestCallers.as(TestCallers.user("get_issue"), () -> callback.call("{}")))
                .isInstanceOf(SanitizedToolException.class)
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).isEqualTo(
                            "{\"error\":\"internal_error\",\"message\":\"The tool could not complete the request.\"}");
                    assertThat(ex.getCause()).isNull();
                });
        assertThat(lastAudit()).contains("\"outcome\":\"error\"", "\"errorCode\":\"internal_error\"");
    }

    @Test
    void credentialRequiredReachesTheClient() {
        ToolCallback callback = decorate(() -> {
            throw new CredentialRequiredException("jira");
        });

        assertThatThrownBy(() -> TestCallers.as(TestCallers.user("get_issue"), () -> callback.call("{}")))
                .hasMessageContaining("\"error\":\"credential_required\"")
                .hasMessageContaining("\"connectUrl\":\"http://localhost:18090/connections?system=jira\"");
    }

    @Test
    void interceptorsRunInOrderAndCanRefuse() {
        ToolCallInterceptor rewrite = (invocation, next) -> next.proceed("{\"rewritten\":true}") + "|first";
        ToolCallInterceptor observe = (invocation, next) -> {
            assertThat(invocation.arguments()).isEqualTo("{\"rewritten\":true}");
            assertThat(invocation.caller().username()).isEqualTo("jkowalski");
            return next.proceed(invocation.arguments()) + "|second";
        };
        ToolCallback callback = decorate(() -> "tool", rewrite, observe);

        assertThat(TestCallers.as(TestCallers.user("get_issue"), () -> callback.call("{}"))).isEqualTo("tool|second|first");

        ToolCallInterceptor deny = (invocation, next) -> {
            throw new SanitizedToolException(new McpErrorMapper.SafeError("not_found_or_denied", "Not found."));
        };
        ToolCallback denied = decorate(() -> {
            throw new AssertionError("must not run");
        }, deny);
        assertThatThrownBy(() -> TestCallers.as(TestCallers.user("get_issue"), () -> denied.call("{}")))
                .hasMessageContaining("not_found_or_denied");
        assertThat(lastAudit()).contains("\"outcome\":\"denied\"");
    }
}
