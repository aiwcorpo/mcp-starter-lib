package pl.aiwcorpo.mcp.platform.test.excluding;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import pl.aiwcorpo.mcp.platform.server.PlatformMcpServerAutoConfiguration;

/** A server that switches the platform's MCP server customizer off in code: the SDK would validate input before the audit. */
@SpringBootApplication(exclude = PlatformMcpServerAutoConfiguration.class)
public class ExcludingMcpServer {
}
