package pl.aiwcorpo.mcp.platform.guard;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class RegisteredToolsCheckTest {

    private final ToolRiskRegistry registry = new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL);

    private RegisteredToolsCheck check(String... listed) {
        McpSyncServer server = mock(McpSyncServer.class);
        when(server.listTools()).thenReturn(List.of(listed).stream()
                .map(name -> McpSchema.Tool.builder(name, Map.of("type", "object")).build()).toList());
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("server", server);
        return new RegisteredToolsCheck(beans.getBeanProvider(McpSyncServer.class), registry);
    }

    @Test
    void aListedToolThatDidNotGoThroughTheDecoratorStopsTheServer() {
        registry.register(new Flagged("get_issue"));

        assertThatCode(() -> check("get_issue").afterSingletonsInstantiated()).doesNotThrowAnyException();
        assertThatThrownBy(() -> check("get_issue", "annotated_delete").afterSingletonsInstantiated())
                .hasMessageContaining("annotated_delete");
    }

    private record Flagged(String name) implements ToolCallback, RiskClassifiedTool {

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

        @Override
        public RiskLevel riskLevel() {
            return RiskLevel.READ_ONLY;
        }
    }
}
