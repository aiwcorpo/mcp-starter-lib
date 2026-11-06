package pl.aiwcorpo.mcp.platform.test;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import pl.aiwcorpo.mcp.platform.policy.PolicyData;
import pl.aiwcorpo.mcp.platform.policy.ServerPolicies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code GET /internal/v1/policy-data} of {@link TestGateway} (C7): the scope mapping, the allowed AI clients and the emergency
 * overrides a server evaluates with, served with an {@code ETag} like the gateway does, over plain http on loopback (the {@code dev}
 * profile). Every change makes a new revision, which the server picks up at its next refresh or at once when a token names it.
 *
 * <pre>{@code
 * GATEWAY.policyData().scopeMapping(Map.of("mcp-jira-proj-ABC", List.of("Jira::Project/ABC")));
 * GATEWAY.policyData().allowedClients("claude-code");
 * GATEWAY.policyData().override("override-no-delete", "forbid (principal, action == Jira::Action::\"delete\", resource);");
 * }</pre>
 *
 * The mapping starts as the one of {@code test-vectors/fixtures/scope-mapping.json} of the contracts, the clients as
 * {@code claude-code}, {@code claude-ai} and {@code local-gateway}.
 *
 * <p>Like the gateway it serves only the scopes of the namespace of the server and the {@code approvedPoliciesHash} of that server's
 * policies ({@link #policies}); a test of a server that has not been approved calls {@link #unapproved()}.
 */
public final class PolicyDataEndpoint {

    /** Revision a gateway starts with; the default {@code prv} of {@link TestGateway.Token} is the same, so it asks for nothing. */
    static final long INITIAL_REVISION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private long revision = INITIAL_REVISION - 1;
    private volatile ServerPolicies serverPolicies;
    private volatile boolean approved = true;
    private volatile String approvedHashOverride;
    private final AtomicInteger requests = new AtomicInteger();
    private volatile Map<String, List<String>> scopeMapping = defaultMapping();
    private volatile List<String> allowedClients = List.of("claude-code", "claude-ai", "local-gateway");
    private final Map<String, String> overrides = new LinkedHashMap<>();
    private record Served(long revision, byte[] body) {
    }

    private volatile Served served;
    private volatile boolean offline;
    private volatile boolean unregistered;
    private volatile boolean stalled;

    PolicyDataEndpoint(ServerPolicies serverPolicies) {
        this.serverPolicies = serverPolicies;
        render();
    }

    private static Map<String, List<String>> defaultMapping() {
        try (java.io.InputStream in = PolicyDataEndpoint.class.getClassLoader()
                .getResourceAsStream("mcp-contracts/test-vectors/fixtures/scope-mapping.json")) {
            if (in == null) {
                return Map.of();
            }
            Map<String, List<String>> mapping = new LinkedHashMap<>();
            JSON.readTree(in.readAllBytes()).forEach(entry -> {
                List<String> scopes = new ArrayList<>();
                entry.path("scopes").forEach(scope -> scopes.add(scope.asString()));
                mapping.put(entry.path("group").asString(), scopes);
            });
            return mapping;
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    /** The policies of the server under test, whose hash is approved and whose namespace filters the mapping. */
    public synchronized PolicyDataEndpoint policies(ServerPolicies policies) {
        this.serverPolicies = policies;
        return render();
    }

    /** Serves {@code json} as the next revision, as it is (for a test of a document that is malformed). The document should carry its own {@code revision}. */
    public synchronized PolicyDataEndpoint rawDocument(long revision, String json) {
        this.revision = revision;
        this.served = new Served(revision, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return this;
    }

    /** The next change goes out with revision {@code next}: for a test that the server never takes data that is not newer than what it holds. */
    public synchronized PolicyDataEndpoint nextRevision(long next) {
        this.revision = next - 1;
        return this;
    }

    /** Serves this hash as the approved one, whatever the policies of the server are (for a test of a server whose policies changed). */
    public synchronized PolicyDataEndpoint approvedPoliciesHash(String hash) {
        this.approvedHashOverride = hash;
        this.approved = true;
        return render();
    }

    /** Serves no {@code approvedPoliciesHash}: the administrator has not approved the policies of the server, which then refuses every call. */
    public synchronized PolicyDataEndpoint unapproved() {
        this.approved = false;
        return render();
    }

    /** Approves the hash of the policies of the server again. */
    public synchronized PolicyDataEndpoint approved() {
        this.approved = true;
        this.approvedHashOverride = null;
        return render();
    }

    /** Replaces the whole mapping: group name to the scope ids ({@code <Type>/<id>}) it grants. */
    public synchronized PolicyDataEndpoint scopeMapping(Map<String, List<String>> scopesByGroup) {
        this.scopeMapping = new LinkedHashMap<>(scopesByGroup);
        return render();
    }

    /** Replaces the allowed AI clients ({@code azp}); none allows no one. */
    public synchronized PolicyDataEndpoint allowedClients(String... clients) {
        this.allowedClients = List.of(clients);
        return render();
    }

    /** Adds or replaces an emergency override: its id ({@code override-<name>}) and the Cedar text of one {@code forbid} policy. */
    public synchronized PolicyDataEndpoint override(String id, String text) {
        overrides.put(id, text);
        return render();
    }

    public synchronized PolicyDataEndpoint removeOverride(String id) {
        overrides.remove(id);
        return render();
    }

    /** While true every request is answered 503, as when the gateway is down. */
    public PolicyDataEndpoint offline(boolean value) {
        this.offline = value;
        return this;
    }

    /** While true every request is answered 403, as for a server that is not in the gateway's registry. */
    public PolicyDataEndpoint unregistered(boolean value) {
        this.unregistered = value;
        return this;
    }

    /**
     * While true a request is answered with the headers of a 200 and then nothing for half a minute, as a gateway (or a proxy in front
     * of it) that hangs while it sends the body.
     */
    public PolicyDataEndpoint stall(boolean value) {
        this.stalled = value;
        return this;
    }

    /** The revision being served. */
    public long revision() {
        return served.revision();
    }

    /** The data being served, as a server parses it. */
    public PolicyData current() {
        return PolicyData.parse(served.body());
    }

    /** How many times the endpoint was requested. */
    public int requests() {
        return requests.get();
    }

    private PolicyDataEndpoint render() {
        long next = ++revision;
        ObjectNode root = JSON.createObjectNode();
        root.put("revision", next);
        ServerPolicies policies = serverPolicies;
        if (approved) {
            String hash = approvedHashOverride != null ? approvedHashOverride : policies == null ? null : policies.policiesHash();
            if (hash != null) {
                root.put("approvedPoliciesHash", hash);
            }
        }
        ArrayNode mapping = root.putArray("scopeMapping");
        scopeMapping.forEach((group, scopes) -> {
            List<String> kept = scopes.stream().filter(scope -> policies == null || scope.startsWith(policies.namespace() + "::")).toList();
            if (!kept.isEmpty()) {
                ObjectNode entry = mapping.addObject();
                entry.put("group", group);
                ArrayNode list = entry.putArray("scopes");
                kept.forEach(list::add);
            }
        });
        ArrayNode clients = root.putArray("allowedClients");
        allowedClients.forEach(clients::add);
        ArrayNode list = root.putArray("overrides");
        overrides.forEach((id, text) -> {
            ObjectNode entry = list.addObject();
            entry.put("id", id);
            entry.put("text", text);
        });
        this.served = new Served(next, JSON.writeValueAsBytes(root));
        return this;
    }

    void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            requests.incrementAndGet();
            if (offline || unregistered) {
                exchange.sendResponseHeaders(offline ? 503 : 403, -1);
                return;
            }
            Served now = served;
            byte[] current = now.body();
            String etag = "\"" + now.revision() + "\"";
            exchange.getResponseHeaders().set("ETag", etag);
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, current.length);
            try (OutputStream out = exchange.getResponseBody()) {
                if (stalled) {
                    out.write(current, 0, 1);
                    out.flush();
                    try {
                        Thread.sleep(30_000);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                out.write(current);
            }
        }
    }
}
