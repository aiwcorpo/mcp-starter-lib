package pl.aiwcorpo.mcp.platform.test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import pl.aiwcorpo.mcp.platform.policy.PolicyData;
import pl.aiwcorpo.mcp.platform.policy.ServerPolicies;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The policy data of {@link TestGateway} (C7, C14) and the push of it to the server: the scope mapping, the allowed AI clients and the
 * emergency overrides a server evaluates with. Like the real gateway it does not wait to be asked: it posts the document to
 * {@code POST /.well-known/mcp-policy-data} of every server it is attached to, with a service token of the purpose {@code policy-data},
 * and every change is a new revision that goes out at once.
 *
 * <pre>{@code
 * GATEWAY.policyData().scopeMapping(Map.of("mcp-jira-proj-ABC", List.of("Jira::Project/ABC")));
 * GATEWAY.policyData().allowedClients("claude-code");
 * GATEWAY.policyData().override("override-no-delete", "forbid (principal, action == Jira::Action::\"delete\", resource);");
 * }</pre>
 *
 * The server of a Spring test is attached when its web server starts (the listener of this library, driven by the test properties of
 * {@link TestGateway#serverProperties()}); a server run by hand is attached with {@link #attach(URI)}. Changes made before that are
 * in the first push. The mapping starts as the one of {@code test-vectors/fixtures/scope-mapping.json} of the contracts, the clients as
 * {@code claude-code}, {@code claude-ai} and {@code local-gateway}.
 *
 * <p>Like the gateway it serves only the scopes of the namespace of the server and the {@code approvedPoliciesHash} of that server's
 * policies ({@link #policies}); a test of a server that has not been approved calls {@link #unapproved()}.
 */
public final class PolicyDataEndpoint {

    /** Revision a gateway starts with; the default {@code prv} of {@link TestGateway.Token} is the same, so a call asks the server for nothing newer. */
    static final long INITIAL_REVISION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** What a server answered to a push: the status and, for a {@code 200}, the body of C14. */
    public record Ack(int status, long revision, boolean accepted, String gate, String body) {
    }

    private final TestGateway gateway;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final List<URI> targets = new CopyOnWriteArrayList<>();
    private final AtomicInteger pushes = new AtomicInteger();
    private long revision = INITIAL_REVISION - 1;
    private volatile ServerPolicies serverPolicies;
    private volatile boolean approved = true;
    private volatile String approvedHashOverride;
    private volatile Map<String, List<String>> scopeMapping = defaultMapping();
    private volatile List<String> allowedClients = List.of("claude-code", "claude-ai", "local-gateway");
    private final Map<String, String> overrides = new LinkedHashMap<>();
    private volatile byte[] document;
    private volatile boolean pushing = true;

    PolicyDataEndpoint(TestGateway gateway, ServerPolicies serverPolicies) {
        this.gateway = gateway;
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

    /**
     * Attaches a server, given by the origin of its address ({@code http://127.0.0.1:8080}), and pushes the current document to it. From then on
     * every change is pushed to it as well.
     *
     * @return the answer of the server to the first push, or {@code null} when it could not be reached (it is attached all the same: a
     *         {@link #push()} later reaches it once it is up)
     */
    public synchronized Ack attach(URI origin) {
        URI base = URI.create(origin.getScheme() + "://" + origin.getAuthority());
        if (!targets.contains(base)) {
            targets.add(base);
        }
        try {
            return pushTo(base);
        } catch (IOException ex) {
            return null;
        }
    }

    /** Stops pushing to the server at {@code origin}. */
    public void detach(URI origin) {
        targets.remove(URI.create(origin.getScheme() + "://" + origin.getAuthority()));
    }

    /**
     * Pushes the current document, the same revision as before, to every attached server: what the gateway does periodically, and what
     * brings a restarted server (which holds nothing) back. A server that cannot be reached is skipped.
     */
    public synchronized void push() {
        for (URI target : targets) {
            try {
                pushTo(target);
            } catch (IOException ex) {
                // Not reachable now: the next push tries again, as the gateway does.
            }
        }
    }

    /** While false, a change is not pushed: for a test of the server that has not heard of it yet (it is then asked with a newer {@code prv}). */
    public PolicyDataEndpoint pushing(boolean value) {
        this.pushing = value;
        return this;
    }

    /**
     * Pushes the current document to one server and returns its answer; {@link IOException} when it cannot be reached.
     *
     * @param origin scheme, host and port of the server; the path is the one of C14
     */
    public synchronized Ack pushTo(URI origin) throws IOException {
        long sent = revision;
        return post(origin, document, sent);
    }

    /** Posts {@code body} as it is, for a test of a body that is not the document of C14; the token carries {@code prv} {@code revision}. */
    public Ack post(URI origin, byte[] body, long revision) throws IOException {
        TestGateway.Minted minted = gateway.service().use(TestGateway.USE_POLICY_DATA).policyRevision(revision).mint();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin.getScheme() + "://" + origin.getAuthority()
                        + "/.well-known/mcp-policy-data"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        minted.headers().forEach(request::header);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            pushes.incrementAndGet();
            if (response.statusCode() != 200) {
                return new Ack(response.statusCode(), -1, false, null, response.body());
            }
            JsonNode answer = JSON.readTree(response.body());
            return new Ack(200, answer.path("revision").asLong(), answer.path("accepted").asBoolean(), answer.path("gate").asString(),
                    response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", ex);
        }
    }

    /** The policies of the server under test, whose hash is approved and whose namespace filters the mapping. */
    public synchronized PolicyDataEndpoint policies(ServerPolicies policies) {
        this.serverPolicies = policies;
        return changed();
    }

    /** The document is {@code json} as the next revision, as it is (for a test of a document that is malformed); it is pushed like any other. */
    public synchronized PolicyDataEndpoint rawDocument(long revision, String json) {
        this.revision = revision;
        this.document = json.getBytes(StandardCharsets.UTF_8);
        return pushChange();
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
        return changed();
    }

    /** Sends no {@code approvedPoliciesHash}: the administrator has not approved the policies of the server, which then refuses every call. */
    public synchronized PolicyDataEndpoint unapproved() {
        this.approved = false;
        return changed();
    }

    /** Approves the hash of the policies of the server again. */
    public synchronized PolicyDataEndpoint approved() {
        this.approved = true;
        this.approvedHashOverride = null;
        return changed();
    }

    /** Replaces the whole mapping: group name to the scope ids ({@code <Type>/<id>}) it grants. */
    public synchronized PolicyDataEndpoint scopeMapping(Map<String, List<String>> scopesByGroup) {
        this.scopeMapping = new LinkedHashMap<>(scopesByGroup);
        return changed();
    }

    /** Replaces the allowed AI clients ({@code azp}); none allows no one. */
    public synchronized PolicyDataEndpoint allowedClients(String... clients) {
        this.allowedClients = List.of(clients);
        return changed();
    }

    /** Adds or replaces an emergency override: its id ({@code override-<name>}) and the Cedar text of one {@code forbid} policy. */
    public synchronized PolicyDataEndpoint override(String id, String text) {
        overrides.put(id, text);
        return changed();
    }

    public synchronized PolicyDataEndpoint removeOverride(String id) {
        overrides.remove(id);
        return changed();
    }

    /** The revision of the current document. */
    public synchronized long revision() {
        return revision;
    }

    /** The document, as a server parses it. */
    public PolicyData current() {
        return PolicyData.parse(document);
    }

    /** The current document as the JSON text that goes out in a push. */
    public String json() {
        return new String(document, StandardCharsets.UTF_8);
    }

    /** How many pushes were sent, answered or not. */
    public int pushes() {
        return pushes.get();
    }

    private PolicyDataEndpoint changed() {
        render();
        return pushChange();
    }

    private PolicyDataEndpoint pushChange() {
        if (pushing) {
            push();
        }
        return this;
    }

    private void render() {
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
        this.document = JSON.writeValueAsBytes(root);
    }
}
