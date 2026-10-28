package pl.aiwcorpo.mcp.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** A tool that reached the MCP server without the platform decorator (Spring AI's {@code @McpTool}) stops the server. */
class ToolHintsPostProcessorTest {

    private final ToolRiskRegistry registry = new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL);
    private final ToolHintsPostProcessor processor;

    ToolHintsPostProcessorTest() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("registry", registry);
        processor = new ToolHintsPostProcessor(beans.getBeanProvider(ToolRiskRegistry.class));
    }

    private static SyncToolSpecification specification(String name) {
        McpSchema.Tool tool = McpSchema.Tool.builder(name, Map.of("type", "object")).description("d").build();
        return new SyncToolSpecification(tool, (exchange, request) -> null);
    }

    @Test
    void aToolThatBypassedTheDecoratorStopsTheServer() {
        assertThatThrownBy(() -> processor.postProcessAfterInitialization(
                List.of(specification("wipe_everything")), "mcpAnnotatedTools"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'wipe_everything'");
    }

    @Test
    void registeredToolsGetTheirHints() {
        registry.register(new Flagged("vault_destroy", RiskLevel.DESTRUCTIVE));
        registry.register(new Flagged("vault_list", RiskLevel.READ_ONLY));

        Object processed = processor.postProcessAfterInitialization(
                List.of(specification("vault_destroy"), specification("vault_list")), "syncTools");

        assertThat(processed).asInstanceOf(InstanceOfAssertFactories.list(SyncToolSpecification.class))
                .extracting(spec -> spec.tool().annotations().destructiveHint(), spec -> spec.tool().annotations().readOnlyHint())
                .containsExactly(Tuple.tuple(true, false), Tuple.tuple(false, true));
    }

    @Test
    void aNameThatContradictsTheFlagStopsTheServer() {
        assertThatThrownBy(() -> registry.register(new Flagged("delete_issue", RiskLevel.UPDATE)))
                .hasMessageContaining("'delete'");
    }

    @Test
    void otherListsAreLeftAlone() {
        List<String> unrelated = List.of("a", "b");
        assertThat(processor.postProcessAfterInitialization(unrelated, "names")).isSameAs(unrelated);
    }

    private record Flagged(String name, RiskLevel riskLevel) implements ToolCallback, RiskClassifiedTool {

        @Override
        public String title() {
            return "Test tool";
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name(name).description("d").inputSchema("{\"type\":\"object\",\"properties\":{\"issueKey\":{\"type\":\"string\"}},\"additionalProperties\":false}").build();
        }

        @Override
        public String call(String toolInput) {
            return "ok";
        }
    }
}
