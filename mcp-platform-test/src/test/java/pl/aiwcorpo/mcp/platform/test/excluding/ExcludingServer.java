package pl.aiwcorpo.mcp.platform.test.excluding;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import pl.aiwcorpo.mcp.platform.security.PlatformSecurityAutoConfiguration;

/** A server that switches the platform's security configuration off in code, where no property shows it. */
@SpringBootApplication(exclude = PlatformSecurityAutoConfiguration.class)
public class ExcludingServer {
}
