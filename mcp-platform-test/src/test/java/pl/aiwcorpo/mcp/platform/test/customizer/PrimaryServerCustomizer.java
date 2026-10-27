package pl.aiwcorpo.mcp.platform.test.customizer;

import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** What the gitlab server did: its own customizer as the primary one. */
@Configuration
public class PrimaryServerCustomizer {

    @Bean
    @Primary
    McpSyncServerCustomizer toolInputsCheckedByTheTools() {
        return server -> server.immediateExecution(true).validateToolInputs(false);
    }
}
