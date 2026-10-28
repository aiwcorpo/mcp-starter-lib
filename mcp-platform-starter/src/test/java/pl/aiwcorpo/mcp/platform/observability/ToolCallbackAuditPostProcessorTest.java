package pl.aiwcorpo.mcp.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;
import pl.aiwcorpo.mcp.platform.guard.RiskClassifiedTool;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;

class ToolCallbackAuditPostProcessorTest {

    private final ToolCallbackAuditPostProcessor processor;

    ToolCallbackAuditPostProcessorTest() {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("audit", new AuditLogger("test.audit", "mcp-jira"));
        beans.registerSingleton("errors", new McpErrorMapper());
        beans.registerSingleton("registry", new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL));
        processor = new ToolCallbackAuditPostProcessor(beans.getBeanProvider(AuditLogger.class),
                beans.getBeanProvider(McpErrorMapper.class), beans.getBeanProvider(ToolRiskRegistry.class),
                beans.getBeanProvider(ToolCallInterceptor.class));
    }

    @Test
    void wrapsEveryCallbackOfAProviderOnceEvenWhenAskedTwice() {
        Object processed = processor.postProcessAfterInitialization(provider("get_issue"), "tools");

        ToolCallback[] first = ((ToolCallbackProvider) processed).getToolCallbacks();
        ToolCallback[] second = ((ToolCallbackProvider) processed).getToolCallbacks();
        assertThat(first).hasSize(1).allMatch(AuditingToolCallback.class::isInstance);
        assertThat(second[0]).isSameAs(first[0]);
        assertThat(processor.postProcessAfterInitialization(processed, "tools")).isSameAs(processed);
    }

    @Test
    void wrapsAStandAloneToolBean() {
        ToolCallback bare = provider("search_issues").getToolCallbacks()[0];

        Object processed = processor.postProcessAfterInitialization(bare, "searchIssues");

        assertThat(processed).isInstanceOf(AuditingToolCallback.class);
        assertThat(processor.postProcessAfterInitialization(processed, "searchIssues")).isSameAs(processed);
    }

    @Test
    void twoToolsWithOneNameStopTheServer() {
        ((ToolCallbackProvider) processor.postProcessAfterInitialization(provider("get_issue"), "a")).getToolCallbacks();
        ToolCallbackProvider second = (ToolCallbackProvider) processor.postProcessAfterInitialization(provider("get_issue"), "b");

        assertThatThrownBy(second::getToolCallbacks).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'get_issue'");
    }

    @Test
    void leavesUnrelatedBeansAlone() {
        Object bean = "not a tool";
        assertThat(processor.postProcessAfterInitialization(bean, "someBean")).isSameAs(bean);
    }

    private static ToolCallbackProvider provider(String toolName) {
        record Flagged(String name) implements ToolCallback, RiskClassifiedTool {

        @Override
        public String title() {
            return "Test tool";
        }

            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name).description("d").inputSchema("{\"type\":\"object\",\"properties\":{\"issueKey\":{\"type\":\"string\",\"maxLength\":64}},\"additionalProperties\":false}").build();
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
        return () -> new ToolCallback[] {new Flagged(toolName)};
    }
}
