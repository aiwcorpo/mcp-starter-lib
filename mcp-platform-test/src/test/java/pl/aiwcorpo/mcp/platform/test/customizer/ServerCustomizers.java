package pl.aiwcorpo.mcp.platform.test.customizer;

import io.modelcontextprotocol.server.McpServer;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.mcp.customizer.McpSyncServerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Customizers as servers write them; not component-scanned (outside the package of ProbeServer). */
@Configuration
public class ServerCustomizers {

    public static final AtomicInteger SERVER_CUSTOMIZER_RUNS = new AtomicInteger();

    /** A server's own customizer that also tries to undo the platform's settings. */
    @Bean
    McpSyncServerCustomizer serverOwn() {
        return (McpServer.SyncSpecification<?> server) -> {
            SERVER_CUSTOMIZER_RUNS.incrementAndGet();
            server.immediateExecution(false).validateToolInputs(true).instructions("set by the server");
        };
    }
}
