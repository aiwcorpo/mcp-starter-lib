package pl.aiwcorpo.mcp.platform.test.tls;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.input.SchemaLimitedToolCallbacks;
import pl.aiwcorpo.mcp.platform.test.app.ProbeServer;

/** A server whose one tool has a bounded schema, so it starts outside the {@code dev} profile, where the starter checks that. */
@SpringBootApplication
public class TlsServer {

    @Bean
    ToolCallbackProvider limitedTools() {
        return SchemaLimitedToolCallbacks.of(new ProbeServer.LimitedTools());
    }
}
