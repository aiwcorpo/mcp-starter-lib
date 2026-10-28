package pl.aiwcorpo.mcp.platform.test.wide;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;

/** A server that sets the listen address in code: the {@code server.address} property still says loopback. */
@SpringBootApplication
public class WideAddressServer {

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> wildcardAddress() {
        return factory -> {
            try {
                factory.setAddress(InetAddress.getByName("0.0.0.0"));
            } catch (UnknownHostException ex) {
                throw new IllegalStateException(ex);
            }
        };
    }
}
