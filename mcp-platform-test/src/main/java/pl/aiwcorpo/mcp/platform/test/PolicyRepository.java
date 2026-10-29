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
    private volatile boolean stalled;

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

    /**
     * Publishes a bundle generated here for a server that is none of the five systems of the contracts (the template's
     * {@code mcp-example}): the schema of the contracts, the static entities of the contracts' examples (scope groups such as
     * {@code mcp-jira-proj-ABC}), a {@code call} policy per risk for the groups {@code <serverId>-read}, {@code <serverId>-write}
     * and {@code <serverId>-destructive}, and the resource rules of the examples (read, update, restricted data, virtual server
     * narrowing) for the same groups. Signed with the published
     * test key of the main channel (the key of the contracts' vectors; the bundle is for tests only).
     */
    public PolicyRepository publishGenerated(String revision, String serverId) {
        return publishGenerated(revision, serverId, Map.of());
    }

    /**
     * As {@link #publishGenerated(String, String)} with more policy files, for a test of a rule of the server's own (for example one
     * that depends on {@code context.credentialType}).
     *
     * @param extraPolicies file name under {@code policies/} (with {@code .cedar}) to the policy text
     */
    public PolicyRepository publishGenerated(String revision, String serverId, Map<String, String> extraPolicies) {
        try {
            String schema = new String(readResource("mcp-contracts/cedar/mcp.cedarschema"), StandardCharsets.UTF_8);
            StringBuilder policies = new StringBuilder();
            for (String[] row : new String[][] {{"read", "READ_ONLY"}, {"write", "UPDATE"}, {"destructive", "DESTRUCTIVE"}}) {
                policies.append("@id(\"").append(serverId).append("-call-").append(row[0]).append("\")\npermit (\n  principal in UserGroup::\"")
                        .append(serverId).append('-').append(row[0]).append("\",\n  action == Action::\"call\",\n  resource is Tool\n)\n")
                        .append("when { resource.server == \"").append(serverId).append("\" && resource.risk == \"")
                        .append(row[1]).append("\" };\n\n");
            }
            String manifest = "{\"revision\":\"" + revision + "\",\"gitSha\":\"" + revision.substring(15).repeat(6).substring(0, 40)
                    + "\",\"createdAt\":\"2026-10-26T13:00:00Z\",\"schemaVersion\":\"3.1.0\"}";
            Map<String, String> files = new java.util.HashMap<>(Map.of("manifest.json", manifest, "schema.cedarschema", schema,
                    "entities/static.json", new String(readResource("mcp-contracts/cedar/examples/static-entities.json"),
                            StandardCharsets.UTF_8),
                    "policies/call.cedar", policies.toString()));
            // The actions on resources and the restricted and virtual server rules of the contracts' examples, for this
            // server's groups: a test of a read that is allowed, of one that is refused as restricted and of a link that
            // narrows needs the same rules as for the five systems.
            for (String example : new String[] {"read", "update", "forbid-restricted", "virtual-server"}) {
                files.put("policies/" + example + ".cedar", new String(readResource("mcp-contracts/cedar/examples/" + example + ".cedar"),
                        StandardCharsets.UTF_8).replaceAll("UserGroup::\"mcp-(?:jira|gitlab|confluence|servicedesk|opensearch)-(read|write|destructive)\"",
                        "UserGroup::\"" + serverId + "-$1\""));
            }
            extraPolicies.forEach((name, text) -> files.put("policies/" + name, text));
            byte[] archive = gzip(tar(files));
            java.security.Signature signature = java.security.Signature.getInstance("Ed25519");
            String pem = new String(readResource("mcp-contracts/test-vectors/keys/test-policy-bundle.NOT-FOR-PRODUCTION.private.pem"),
                    StandardCharsets.UTF_8).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            signature.initSign(java.security.KeyFactory.getInstance("Ed25519").generatePrivate(
                    new java.security.spec.PKCS8EncodedKeySpec(java.util.Base64.getDecoder().decode(pem))));
            signature.update(archive);
            return publishRaw(revision, archive, signature.sign());
        } catch (java.security.GeneralSecurityException | IOException ex) {
            throw new IllegalStateException("cannot generate the test bundle", ex);
        }
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream in = PolicyRepository.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException(path + " not found: add pl.aiwcorpo.mcp:mcp-contracts and its test-vectors");
            }
            return in.readAllBytes();
        }
    }

    /** Strict ustar, regular files only, as mcp-policy-bundle reads it. */
    private static byte[] tar(Map<String, String> files) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (Map.Entry<String, String> file : new java.util.TreeMap<>(files).entrySet()) {
            byte[] content = file.getValue().getBytes(StandardCharsets.UTF_8);
            byte[] header = new byte[512];
            put(header, 0, file.getKey());
            put(header, 100, "0000644");
            put(header, 108, "0000000");
            put(header, 116, "0000000");
            put(header, 124, String.format("%011o", content.length));
            put(header, 136, "00000000000");
            java.util.Arrays.fill(header, 148, 156, (byte) ' ');
            header[156] = '0';
            put(header, 257, "ustar");
            put(header, 263, "00");
            long sum = 0;
            for (byte b : header) {
                sum += b & 0xff;
            }
            put(header, 148, String.format("%06o", sum));
            header[154] = 0;
            header[155] = ' ';
            out.write(header);
            out.write(content);
            out.write(new byte[(512 - content.length % 512) % 512]);
        }
        out.write(new byte[1024]);
        return out.toByteArray();
    }

    private static void put(byte[] header, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }

    private static byte[] gzip(byte[] data) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(data);
        }
        return out.toByteArray();
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

    /**
     * While true a request is answered with the headers of a 200 and then nothing for half a minute, as a repository
     * (or a proxy in front of it) that hangs while it sends the body.
     */
    public PolicyRepository stall(boolean value) {
        this.stalled = value;
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
                if (stalled) {
                    out.write(body, 0, 1);
                    out.flush();
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
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
