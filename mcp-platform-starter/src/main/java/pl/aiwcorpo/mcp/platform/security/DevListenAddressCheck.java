package pl.aiwcorpo.mcp.platform.security;

import java.util.List;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import pl.aiwcorpo.mcp.platform.autoconfigure.ListenAddresses;
import pl.aiwcorpo.mcp.platform.autoconfigure.PlatformConfigurationGuard;

/**
 * In the {@code dev} profile, stops the start when a web server (the main one or the management one) listens on an
 * address other than loopback, whatever {@code server.address} says: the guard checks the property, this checks the
 * connectors that exist. The dev relaxations (test keys, no TLS, in-memory jti) must not be reachable from the network.
 */
public class DevListenAddressCheck implements ApplicationListener<WebServerInitializedEvent> {

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        Environment environment = event.getApplicationContext().getEnvironment();
        if (!List.of(environment.getActiveProfiles()).equals(List.of(PlatformConfigurationGuard.DEV_PROFILE))) {
            return;
        }
        List<String> exposed = ListenAddresses.nonLoopback(event.getWebServer());
        if (!exposed.isEmpty()) {
            event.getWebServer().stop();
            throw new IllegalStateException("with the 'dev' profile the web server must listen on a loopback address only, "
                    + "but it listens on " + exposed + " (a WebServerFactoryCustomizer or an additional connector set it, "
                    + "server.address does not show it): the dev relaxations must not be reachable from the network");
        }
    }
}
