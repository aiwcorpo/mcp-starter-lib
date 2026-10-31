package pl.aiwcorpo.mcp.platform.test.customizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import pl.aiwcorpo.mcp.platform.test.SpringTestContextStart;
import pl.aiwcorpo.mcp.platform.test.TestGateway;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/** Spring AI takes one McpSyncServerCustomizer; the starter owns it and composes the server's. */
@DisplayName("the MCP server customizer is the starter's, composed with the server's")
class ServerCustomizerTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");

    @AfterAll
    static void stop() {
        GATEWAY.close();
    }

    private static ConfigurableApplicationContext run(Class<?>... extra) {
        Map<String, Object> properties = new HashMap<>(GATEWAY.serverProperties());
        properties.put("server.port", "0");
        properties.put("spring.ai.mcp.server.name", "customizer-test");
        Class<?>[] sources = new Class<?>[extra.length + 1];
        sources[0] = ProbeServer.class;
        System.arraycopy(extra, 0, sources, 1, extra.length);
        return SpringTestContextStart.start(sources, properties, "dev");
    }

    @Test
    @DisplayName("a @Primary customizer of the server stops the start with a pointer to the fix")
    void aPrimaryCustomizerOfTheServerIsRefused() {
        assertThatThrownBy(() -> run(PrimaryServerCustomizer.class).close())
                .hasMessageContaining("toolInputsCheckedByTheTools")
                .hasMessageContaining("@Primary")
                .hasMessageContaining("Remove @Primary");
    }
}
