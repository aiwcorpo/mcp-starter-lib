package pl.aiwcorpo.mcp.platform.test.adapter;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.guard.RiskClassifiedTool;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;

/** The shape of an adapter sidecar: a hand-written callback for a tool of a foreign server, no policies of its own. */
@SpringBootApplication
public class AdapterServer {

    @Bean
    ToolCallbackProvider foreignTools() {
        return ToolCallbackProvider.from(new ForeignTool());
    }

    record ForeignTool() implements ToolCallback, RiskClassifiedTool {

        @Override
        public RiskLevel riskLevel() {
            return RiskLevel.READ_ONLY;
        }

        @Override
        public String title() {
            return "Foreign ping";
        }

        @Override
        public boolean resultWithoutResources() {
            return true;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name("foreign_ping").description("d")
                    .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}").build();
        }

        @Override
        public String call(String toolInput) {
            return "pong";
        }
    }
}
