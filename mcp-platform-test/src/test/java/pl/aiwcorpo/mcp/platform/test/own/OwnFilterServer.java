package pl.aiwcorpo.mcp.platform.test.own;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import pl.aiwcorpo.mcp.platform.security.CredentialHeaderFilter;

/**
 * A server with its own {@link CredentialHeaderFilter}, the platform's only eager dependant of the configuration guard:
 * with lazy initialization nothing else would create the guard.
 */
@SpringBootApplication
public class OwnFilterServer {

    @Bean
    CredentialHeaderFilter credentialHeaderFilter() {
        return new CredentialHeaderFilter("jira", null);
    }
}
