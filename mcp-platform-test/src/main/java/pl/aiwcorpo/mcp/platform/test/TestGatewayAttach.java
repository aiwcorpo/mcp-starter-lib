package pl.aiwcorpo.mcp.platform.test;

import java.net.URI;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;

/**
 * Attaches the server of a Spring test to its {@link TestGateway} when the web server is up, so that the first policy data is pushed before
 * the first test call, as the real gateway does after a server is connected. The gateway is named by a property that
 * {@link TestGateway#serverProperties()} puts in the test context; contexts without it (and servers without a web server) are left alone.
 * Registered in {@code META-INF/spring.factories} of this library.
 */
public final class TestGatewayAttach implements ApplicationListener<WebServerInitializedEvent> {

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        WebServerApplicationContext context = event.getApplicationContext();
        if ("management".equals(context.getServerNamespace())) {
            return;
        }
        String key = context.getEnvironment().getProperty(TestGateway.GATEWAY_PROPERTY);
        boolean tls = context.getEnvironment().getProperty("server.ssl.enabled", Boolean.class, false);
        TestGateway.byKey(key).ifPresent(gateway -> gateway.policyData()
                .attach(URI.create((tls ? "https" : "http") + "://127.0.0.1:" + event.getWebServer().getPort())));
    }
}
