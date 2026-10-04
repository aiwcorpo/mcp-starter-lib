package pl.aiwcorpo.mcp.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.http.HttpStatus;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;
import pl.aiwcorpo.mcp.platform.security.MissingPatException;

/**
 * The contract that matters: nothing an upstream backend said may reach the AI client, while
 * messages we authored ourselves (validation, missing credential) must survive so the model can fix
 * its call.
 */
class AuditingToolCallbackTest {

    private final AuditLogger audit = new AuditLogger("test.audit");
    private final McpErrorMapper errors = new McpErrorMapper();

    private ToolCallback decorate(Supplier<String> behaviour) {
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name("pve_vm_status").description("test").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return behaviour.get();
            }
        };
        return new AuditingToolCallback(delegate, audit, errors);
    }

    @Test
    void passesThroughResultAndDefinitionOnSuccess() {
        ToolCallback callback = decorate(() -> "{\"status\":\"running\"}");

        assertThat(callback.getToolDefinition().name()).isEqualTo("pve_vm_status");
        assertThat(callback.call("{}")).isEqualTo("{\"status\":\"running\"}");
    }

    @Test
    void backendErrorNeverReachesTheModel() {
        // Exactly what RestClient throws: the message carries the upstream status AND response body.
        ToolCallback callback = decorate(() -> {
            throw HttpClientErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                    null, "{\"errors\":\"root@pam auth failed at https://pve.internal:8006\"}".getBytes(), null);
        });

        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(SanitizedToolException.class)
                .hasMessage("internal_error: The tool could not complete the request.")
                .satisfies(ex -> {
                    assertThat(ex.getMessage()).doesNotContain("pve.internal");
                    assertThat(ex.getMessage()).doesNotContain("root@pam");
                    assertThat(ex.getCause()).isNull();   // no cause -> nothing to unwrap and print
                });
    }

    @Test
    void validationMessageIsEchoedSoTheModelCanCorrectItself() {
        ToolCallback callback = decorate(() -> {
            throw new IllegalArgumentException("vmid must be in 100..999999999");
        });

        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(SanitizedToolException.class)
                .hasMessage("invalid_argument: vmid must be in 100..999999999");
    }

    @Test
    void missingPatTellsTheCallerWhichHeaderToSend() {
        ToolCallback callback = decorate(() -> {
            throw new MissingPatException("pve");
        });

        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(SanitizedToolException.class)
                .hasMessage("missing_credential: Missing PAT header X-PAT-Pve");
    }

    @Test
    void genericSecurityExceptionStaysOpaque() {
        ToolCallback callback = decorate(() -> {
            throw new SecurityException("keystore /etc/mcp/tls/keystore.p12 unreadable");
        });

        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(SanitizedToolException.class)
                .hasMessage("forbidden: Not authorized for this operation.")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("keystore.p12"));
    }

    @Test
    void oversizedValidationMessageIsTruncated() {
        String huge = "x".repeat(2_000);
        ToolCallback callback = decorate(() -> {
            throw new IllegalArgumentException(huge);
        });

        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(SanitizedToolException.class)
                .satisfies(ex -> assertThat(ex.getMessage().length()).isLessThan(600));
    }
}
