package pl.aiwcorpo.mcp.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The last line of defence at startup: a tool that reached the MCP server without going through the
 * guard (Spring AI's {@code @McpTool} is the practical way to do that) must stop the server.
 */
class ToolHintsPostProcessorTest {

    private final ToolGuard guard = new ToolGuard(
            new GuardPolicyStore(null, 1_000),
            new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL),
            new ConfirmationService((identity, tool, summary, code, expiresAt) -> { }),
            (context, message) -> java.util.Optional.empty());

    private final ToolHintsPostProcessor processor = new ToolHintsPostProcessor(new ObjectProvider<>() {
        @Override
        public ToolGuard getObject() {
            return guard;
        }

        @Override
        public ToolGuard getIfAvailable() {
            return guard;
        }
    });

    private static SyncToolSpecification specification(String name) {
        McpSchema.Tool tool = McpSchema.Tool.builder(name, Map.of("type", "object")).description("d").build();
        return new SyncToolSpecification(tool, (exchange, request) -> null);
    }

    @Test
    void aToolThatBypassedTheGuardStopsTheServer() {
        assertThatThrownBy(() -> processor.postProcessAfterInitialization(
                List.of(specification("wipe_everything")), "mcpAnnotatedTools"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'wipe_everything'")
                .hasMessageContaining("not protected by the platform guard");
    }

    @Test
    void guardedToolsGetTheirHints() {
        guard.register(new Guarded("vault_destroy", RiskLevel.DESTRUCTIVE));
        guard.register(new Guarded("vault_list", RiskLevel.READ_ONLY));

        Object processed = processor.postProcessAfterInitialization(
                List.of(specification("vault_destroy"), specification("vault_list")), "syncTools");

        assertThat(processed).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(SyncToolSpecification.class))
                .extracting(spec -> spec.tool().annotations().destructiveHint(), spec -> spec.tool().annotations().readOnlyHint())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(true, false), org.assertj.core.groups.Tuple.tuple(false, true));
    }

    @Test
    void otherListsAreLeftAlone() {
        List<String> unrelated = List.of("a", "b");
        assertThat(processor.postProcessAfterInitialization(unrelated, "names")).isSameAs(unrelated);
        assertThat(processor.postProcessAfterInitialization(List.of(), "empty")).isEqualTo(List.of());
    }

    private record Guarded(String name, RiskLevel riskLevel) implements ToolCallback, RiskClassifiedTool {

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name(name).description("d").inputSchema("{}").build();
        }

        @Override
        public String call(String toolInput) {
            return "ok";
        }
    }
}
