package pl.aiwcorpo.mcp.platform.test.bypass;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;


/** Tools that try to reach the MCP server around the platform's decorator. */
@SpringBootApplication
public class McpToolServer {

    @Bean
    AnnotatedTools annotatedTools() {
        return new AnnotatedTools();
    }

    @Bean
    @ConditionalOnProperty("bypass.tool-specification-bean")
    McpServerFeatures.SyncToolSpecification handMadeSpecification() {
        McpSchema.Tool tool = McpSchema.Tool.builder("hand_made", Map.of("type", "object")).description("d").build();
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> null);
    }

    public static class AnnotatedTools {

        @McpTool(name = "annotated_delete", description = "Unflagged and unaudited if it got in.")
        public String annotatedDelete() {
            return "deleted";
        }
    }
}
