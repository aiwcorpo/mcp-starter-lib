package pl.aiwcorpo.mcp.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper;

/**
 * Every tool a server exposes must come out decorated — a server that forgets to opt in is exactly
 * the failure mode this post-processor exists to prevent.
 */
class ToolCallbackAuditPostProcessorTest {

    private final ToolCallbackAuditPostProcessor processor = new ToolCallbackAuditPostProcessor(
            fixed(new AuditLogger("test.audit")), fixed(new McpErrorMapper()));

    @Test
    void wrapsEveryCallbackOfAToolCallbackProviderBean() {
        Object processed = processor.postProcessAfterInitialization(provider("gitlab_get_issue"), "mcpTools");

        assertThat(processed).isInstanceOf(ToolCallbackProvider.class);
        ToolCallback[] callbacks = ((ToolCallbackProvider) processed).getToolCallbacks();
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks[0]).isInstanceOf(AuditingToolCallback.class);
        assertThat(callbacks[0].getToolDefinition().name()).isEqualTo("gitlab_get_issue");
    }

    @Test
    void doesNotDoubleWrapOnASecondPass() {
        Object once = processor.postProcessAfterInitialization(provider("pbs_list_snapshots"), "mcpTools");
        Object twice = processor.postProcessAfterInitialization(once, "mcpTools");

        assertThat(twice).isSameAs(once);
        ToolCallback inner = ((ToolCallbackProvider) twice).getToolCallbacks()[0];
        assertThat(inner).isInstanceOf(AuditingToolCallback.class);
    }

    @Test
    void wrapsAToolDeclaredAsAStandAloneBean() {
        ToolCallback bare = provider("jira_get_issue").getToolCallbacks()[0];

        Object processed = processor.postProcessAfterInitialization(bare, "jiraGetIssue");

        assertThat(processed).isInstanceOf(AuditingToolCallback.class);
        assertThat(processor.postProcessAfterInitialization(processed, "jiraGetIssue")).isSameAs(processed);
    }

    @Test
    void leavesUnrelatedBeansAlone() {
        Object bean = "not a tool provider";
        assertThat(processor.postProcessAfterInitialization(bean, "someBean")).isSameAs(bean);
    }

    private static ToolCallbackProvider provider(String toolName) {
        ToolCallback callback = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(toolName).description("d").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
        return () -> new ToolCallback[] {callback};
    }

    /** Minimal ObjectProvider that always yields the same instance. */
    private static <T> ObjectProvider<T> fixed(T instance) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return instance;
            }

            @Override
            public T getObject(Object... args) {
                return instance;
            }

            @Override
            public T getIfAvailable() {
                return instance;
            }

            @Override
            public T getIfUnique() {
                return instance;
            }
        };
    }
}
