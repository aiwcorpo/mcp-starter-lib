package pl.aiwcorpo.mcp.platform.test;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** An OTLP/HTTP traces endpoint that keeps the request bodies (protobuf, uncompressed) so a test can search them. */
final class TraceCollectorStub implements AutoCloseable {

    private final HttpServer server;
    private final List<byte[]> bodies = new CopyOnWriteArrayList<>();

    TraceCollectorStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/traces", exchange -> {
            try (exchange) {
                bodies.add(exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(200, -1);
            }
        });
        server.start();
    }

    String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/traces";
    }

    byte[] exported() {
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        bodies.forEach(all::writeBytes);
        return all.toByteArray();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
