package pl.aiwcorpo.mcp.platform.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;

/** The addresses read from a started Tomcat, which is what the dev profile's check relies on. */
class ListenAddressesTest {

    private static List<String> exposed(InetAddress address) {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        factory.setAddress(address);
        WebServer server = factory.getWebServer();
        server.start();
        try {
            return ListenAddresses.nonLoopback(server);
        } finally {
            server.stop();
        }
    }

    @Test
    void loopbackIsNotReported() throws Exception {
        assertThat(exposed(InetAddress.getByName("127.0.0.1"))).isEmpty();
    }

    @Test
    void aWildcardAndAnUnsetAddressAreReported() throws Exception {
        assertThat(exposed(InetAddress.getByName("0.0.0.0"))).singleElement().asString().contains("0.0.0.0");
        assertThat(exposed(null)).singleElement().asString().contains("all interfaces");
    }
}
