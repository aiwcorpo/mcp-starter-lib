package pl.aiwcorpo.mcp.platform.test;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One channel of the policy repository a server pulls from (C7): {@code latest.json} with an ETag, the archive and the
 * signature, served from the signed bundles of mcp-contracts ({@code test-vectors/bundles/files}) with the published
 * test keys. {@link TestGateway} starts one for the main and one for the overrides channel.
 *
 * <pre>{@code
 * GATEWAY.main().publish("20261026130000-b2c3d4e");           // the bundle with the example policies of the contracts
 * GATEWAY.main().publish("20261026130000-b2c3d4e", "policies-20261026130000-b2c3d4e.tar.gz.sig-by-overrides-key");
 * }</pre>
 * The bundles of the contracts for the main channel: {@code 20261026120000-a1b2c3d}, {@code 20261026130000-b2c3d4e}
 * (the example policies of {@code cedar/examples}), {@code 20261026140000-c3d4e5f}; for overrides
 * {@code 20261026121500-d4e5f60} (forbid rules only). The keys are public: tests only.
 */
public final class PolicyRepository {

    private static final String FILES = "mcp-contracts/test-vectors/bundles/files/";

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final AtomicInteger pointerRequests = new AtomicInteger();
    private volatile String revision;
    private volatile boolean offline;

    /** Publishes the bundle of {@code revision} with its own signature. */
    public PolicyRepository publish(String revision) {
        return publish(revision, "policies-" + revision + ".tar.gz.sig");
    }

    /** Publishes the bundle of {@code revision} with the signature from the file {@code signatureResource} of the bundle files. */
    public PolicyRepository publish(String revision, String signatureResource) {
        String archive = "policies-" + revision + ".tar.gz";
        byte[] archiveBytes = read(archive);
        byte[] signature = read(signatureResource);
        return publishRaw(revision, archiveBytes, signature);
    }

    /** Publishes arbitrary bytes under the names the pointer of {@code revision} gives. */
    public PolicyRepository publishRaw(String revision, byte[] archive, byte[] signature) {
        String name = "policies-" + revision + ".tar.gz";
        files.put(name, archive);
        files.put(name + ".sig", signature);
        files.put("latest.json", ("{\"revision\":\"" + revision + "\",\"url\":\"" + name + "\",\"sigUrl\":\"" + name
                + ".sig\"}").getBytes(StandardCharsets.UTF_8));
        this.revision = revision;
        return this;
    }

    /** While true every request is answered 503, as when the repository is down. */
    public PolicyRepository offline(boolean value) {
        this.offline = value;
        return this;
    }

    /** The revision {@code latest.json} points at, or {@code null}. */
    public String revision() {
        return revision;
    }

    /** How many times {@code latest.json} was requested. */
    public int pointerRequests() {
        return pointerRequests.get();
    }

    void handle(HttpExchange exchange, String name) throws IOException {
        try (exchange) {
            byte[] body = files.get(name);
            if (offline || body == null) {
                exchange.sendResponseHeaders(offline ? 503 : 404, -1);
                return;
            }
            if ("latest.json".equals(name)) {
                pointerRequests.incrementAndGet();
                String etag = "\"" + revision + "\"";
                exchange.getResponseHeaders().set("ETag", etag);
                if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                    exchange.sendResponseHeaders(304, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static byte[] read(String file) {
        try (InputStream in = PolicyRepository.class.getClassLoader().getResourceAsStream(FILES + file)) {
            if (in == null) {
                throw new IllegalArgumentException(FILES + file + " not found in mcp-contracts:test-vectors");
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
