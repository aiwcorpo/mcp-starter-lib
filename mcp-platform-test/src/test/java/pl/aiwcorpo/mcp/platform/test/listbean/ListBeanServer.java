package pl.aiwcorpo.mcp.platform.test.listbean;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.guard.ToolTitle;
import pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources;

/** A server that exposes its tools as a {@code List<ToolCallback>} bean, optionally also through a provider. */
@SpringBootApplication
public class ListBeanServer {

    @Bean
    ListedTools listedTools() {
        return new ListedTools();
    }

    @Bean
    List<ToolCallback> listedToolCallbacks(ListedTools tools) {
        return List.of(ToolCallbacks.from(tools));
    }

    @Bean
    @ConditionalOnProperty("listbean.provider")
    ToolCallbackProvider listedToolProvider(ListedTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    public static class ListedTools {

        public static final AtomicInteger CALLS = new AtomicInteger();

        @Tool(name = "echo_text", description = "Echoes a text.")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Test tool")
        @ResultWithoutResources
        public String echoText(String text) {
            CALLS.incrementAndGet();
            return text;
        }
    }
}
