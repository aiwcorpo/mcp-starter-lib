package pl.aiwcorpo.mcp.platform.autoconfigure;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.apache.catalina.connector.Connector;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;

/**
 * The addresses a started web server really listens on. {@code server.address} is only a property: code can set the
 * address of the server factory (a {@code WebServerFactoryCustomizer}) or add a connector, which listens on every
 * interface unless told otherwise, without the property changing.
 */
public final class ListenAddresses {

    private ListenAddresses() {
    }

    /** Descriptions of the connectors of {@code server} that are not bound to a loopback address; empty when all are. */
    public static List<String> nonLoopback(WebServer server) {
        List<String> found = new ArrayList<>();
        if (server instanceof TomcatWebServer tomcat) {
            for (Connector connector : tomcat.getTomcat().getService().findConnectors()) {
                Object address = connector.getProperty("address");
                boolean loopback = address instanceof InetAddress inet
                        ? inet.isLoopbackAddress()
                        : address != null && PlatformConfigurationGuard.loopback(address.toString());
                if (!loopback) {
                    found.add("port " + connector.getLocalPort() + " on " + (address == null ? "all interfaces" : address));
                }
            }
        }
        return found;
    }
}
