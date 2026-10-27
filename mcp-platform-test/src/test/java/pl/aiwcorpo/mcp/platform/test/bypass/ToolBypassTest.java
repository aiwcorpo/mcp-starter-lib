package pl.aiwcorpo.mcp.platform.test.bypass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import pl.aiwcorpo.mcp.platform.test.TestGateway;

/** Review 3.0.0-alpha-1 H1: no tool reaches the MCP server without the platform's decorator. */
class ToolBypassTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");

    @AfterAll
    static void stop() {
        GATEWAY.close();
    }

    private static ConfigurableApplicationContext start(Map<String, Object> overrides, String profile) {
        Map<String, Object> properties = new HashMap<>(GATEWAY.serverProperties());
        properties.put("server.port", "0");
        properties.putAll(overrides);
        return new SpringApplicationBuilder(McpToolServer.class).web(WebApplicationType.SERVLET)
                .properties(properties).profiles(profile).run();
    }

    @Test
    void byDefaultMcpToolIsNotScannedAndNotListed() {
        try (ConfigurableApplicationContext context = start(Map.of(), "dev")) {
            assertThat(context.getBean(McpSyncServer.class).listTools()).extracting(McpSchema.Tool::name)
                    .doesNotContain("annotated_delete");
        }
    }

    /**
     * With the scanner switched back on, an {@code @McpTool} must not become callable around the decorator: the
     * server either refuses to start ({@code RegisteredToolsCheck}, {@code ToolHintsPostProcessor}) or does not
     * list the tool. Spring AI 2.0.0 does not register it in this setup; the checks are for when it does.
     */
    @Test
    void anMcpToolWithTheScannerSwitchedOnIsNeverListedUnchecked() {
        ConfigurableApplicationContext context;
        try {
            context = start(Map.of("spring.ai.mcp.server.annotation-scanner.enabled", "true"), "dev");
        } catch (RuntimeException refused) {
            assertThat(refused).rootCause().hasMessageContaining("annotated_delete");
            return;
        }
        try (context) {
            assertThat(context.getBean(McpSyncServer.class).listTools()).extracting(McpSchema.Tool::name)
                    .doesNotContain("annotated_delete");
        }
    }

    @Test
    void aToolSpecificationBeanStopsTheServer() {
        assertThatThrownBy(() -> start(Map.of("bypass.tool-specification-bean", "true"), "dev"))
                .rootCause().hasMessageContaining("hand_made");
    }
}
