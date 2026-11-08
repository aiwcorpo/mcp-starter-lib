package pl.aiwcorpo.mcp.platform.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import pl.aiwcorpo.mcp.manifest.DefinitionHash;
import pl.aiwcorpo.mcp.manifest.ManifestRules;
import pl.aiwcorpo.mcp.platform.manifest.ManifestGenerator;
import pl.aiwcorpo.mcp.platform.test.resource.JiraServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The C6 manifest the server serves: it passes the schema and the semantic rules of the contracts (the gateway's
 * checks), its definitions are exactly the entries of {@code tools/list}, so its hashes are what the gateway computes from
 * them, and a DESTRUCTIVE tool is declared and published as destructive.
 */
@SpringBootTest(classes = JiraServer.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class ManifestTest {

    static final TestGateway GATEWAY = TestGateway.start("mcp-jira");
    static final Path FILE = Path.of("target", "manifest-test", "mcp-manifest.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void gateway(DynamicPropertyRegistry registry) {
        GATEWAY.serverProperties().forEach((name, value) -> registry.add(name, () -> value));
        registry.add("aiwcorpo.mcp.platform.manifest.output-file", FILE::toString);
    }

    @AfterAll
    static void stopGateway() {
        GATEWAY.close();
    }

    @LocalServerPort
    int port;

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        headers.forEach(request::header);
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode manifest() throws Exception {
        HttpResponse<String> response = get("/.well-known/mcp-manifest", GATEWAY.manifestToken().mint().headers());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(value -> assertThat(value).contains("no-store"));
        return JSON.readTree(response.body());
    }

    private List<JsonNode> listedTools() throws Exception {
        RawMcp raw = new RawMcp(port);
        String session = raw.openSession(GATEWAY);
        HttpResponse<String> response = raw.post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\"}",
                GATEWAY.user().mint().headers(), session);
        String body = response.body();
        int data = body.indexOf("data:");
        JsonNode message = JSON.readTree(data >= 0 ? body.substring(data + 5).strip() : body);
        List<JsonNode> tools = new ArrayList<>();
        message.at("/result/tools").forEach(tools::add);
        return tools;
    }

    @Test
    void theManifestPassesTheSchemaAndTheRulesOfTheGateway() throws Exception {
        JsonNode manifest = manifest();

        ManifestGenerator.check(manifest.toString(), true);
        assertThat(ManifestRules.violations(manifest.toString())).isEmpty();
        assertThat(manifest.get("serverId").asString()).isEqualTo("mcp-jira");
        assertThat(manifest.get("system").asString()).isEqualTo("jira");
        assertThat(manifest.at("/credential/acceptedTypes").toString()).isEqualTo("[\"user-secret\",\"user-oauth\"]");
    }

    @Test
    void theAudiencePendingIsRefusedEverywhereOnceTheServerHoldsPolicyData() throws Exception {
        HttpResponse<String> manifest = get("/.well-known/mcp-manifest", GATEWAY.manifestToken().audience("mcp-pending").mint().headers());
        assertThat(manifest.statusCode()).as("this server was pushed policy data when it started: mcp-pending is no longer accepted").isEqualTo(401);
        assertThat(manifest.body()).contains("\"reason\":\"wrong_audience\"");
        assertThat(get("/.well-known/mcp-manifest", GATEWAY.manifestToken().mint().headers()).statusCode())
                .as("the real server id still reads the manifest").isEqualTo(200);

        HttpResponse<String> callAtManifest = get("/.well-known/mcp-manifest", GATEWAY.service().audience("mcp-pending").mint().headers());
        assertThat(callAtManifest.statusCode()).isEqualTo(401);
        assertThat(callAtManifest.body()).contains("\"reason\":\"wrong_audience\"");

        HttpRequest.Builder push = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/.well-known/mcp-policy-data"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        GATEWAY.service().use(TestGateway.USE_POLICY_DATA).audience("mcp-pending").mint().headers().forEach(push::header);
        HttpResponse<String> policyData = HttpClient.newHttpClient().send(push.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(policyData.statusCode()).isEqualTo(401);
        assertThat(policyData.body()).contains("\"reason\":\"wrong_audience\"");

        RawMcp raw = new RawMcp(port);
        HttpResponse<String> toolsList = raw.post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/list\"}",
                GATEWAY.service().audience("mcp-pending").mint().headers(), null);
        assertThat(toolsList.statusCode()).isEqualTo(401);
    }

    @Test
    void theDefinitionsAreExactlyWhatToolsListPublishesSoTheHashesAgree() throws Exception {
        JsonNode manifest = manifest();
        List<JsonNode> listed = listedTools();

        assertThat(listed).hasSameSizeAs(manifest.get("tools"));
        for (JsonNode tool : manifest.get("tools")) {
            JsonNode published = listed.stream().filter(t -> t.get("name").asString().equals(tool.get("name").asString()))
                    .findFirst().orElseThrow();
            assertThat(tool.get("definition")).as(tool.get("name").asString()).isEqualTo(published);
            assertThat(DefinitionHash.ofManifestTool(manifest.toString(), tool.get("name").asString()))
                    .isEqualTo(tool.get("definitionHash").asString());
        }
    }

    @Test
    void everyToolHasATitleAndTheInputSchemaIsClosed() throws Exception {
        for (JsonNode tool : manifest().get("tools")) {
            assertThat(tool.at("/definition/title").asString()).isNotBlank();
            assertThat(tool.at("/definition/inputSchema/additionalProperties").asBoolean(true)).isFalse();
        }
    }

    @Test
    void aDestructiveToolIsDeclaredAndPublishedAsDestructive() throws Exception {
        JsonNode delete = tool(manifest(), "delete_issue");

        assertThat(delete.get("risk").asString()).isEqualTo("DESTRUCTIVE");
        assertThat(delete.get("action").asString()).isEqualTo("delete");
        assertThat(delete.at("/definition/annotations/destructiveHint").asBoolean()).isTrue();
        assertThat(delete.at("/definition/annotations/readOnlyHint").asBoolean(true)).isFalse();
        assertThat(tool(manifest(), "get_issue").get("risk").asString()).isEqualTo("READ_ONLY");
        assertThat(tool(manifest(), "transfer_issue").get("risk").asString()).isEqualTo("UPDATE");
    }

    @Test
    void theResourceRefsAndTheTypesTheyNeedAreDeclared() throws Exception {
        JsonNode manifest = manifest();

        assertThat(tool(manifest, "transfer_issue").get("resourceRefs").toString())
                .isEqualTo("[{\"argument\":\"issueKey\",\"type\":\"Jira::Issue\"},{\"argument\":\"targetProject\",\"type\":\"Jira::Project\"}]");
        assertThat(tool(manifest, "find_issues").get("resourceRefs")).isEmpty();
        List<String> types = new ArrayList<>();
        manifest.get("resourceTypes").forEach(type -> types.add(type.get("type").asString()));
        assertThat(types).containsExactlyInAnyOrder("Jira::Project", "Jira::Issue");
        assertThat(manifest.at("/resourceTypes/0/idPattern").asString()).startsWith("^");
    }

    @Test
    void thePoliciesOfTheJarAndTheirHashAreInTheManifest() throws Exception {
        JsonNode manifest = manifest();

        assertThat(manifest.at("/policies/namespace").asString()).isEqualTo("Jira");
        assertThat(manifest.at("/policies/schema").asString()).contains("namespace Jira");
        List<String> names = new ArrayList<>();
        manifest.at("/policies/files").forEach(file -> names.add(file.get("name").asString()));
        assertThat(names).containsExactly("forbid-restricted.cedar", "read.cedar", "update.cedar");
        assertThat(manifest.get("policiesHash").asString()).isEqualTo(pl.aiwcorpo.mcp.manifest.PoliciesHash.of(
                manifest.get("policies").toString(), manifest.get("resourceTypes").toString()));
    }

    @Test
    void theManifestFileOfTheBuildIsTheDocumentTheEndpointServes() throws Exception {
        assertThat(JSON.readTree(Files.readString(FILE))).isEqualTo(manifest());
    }

    @Test
    void theManifestNeedsAnInternalJwtOfThePurposeManifest() throws Exception {
        String path = "/.well-known/mcp-manifest";
        assertThat(get(path, Map.of()).statusCode()).isEqualTo(401);
        assertThat(get(path, GATEWAY.user().tool("get_issue").mint().headers()).statusCode())
                .as("the manifest is not a tool call").isEqualTo(401);

        HttpResponse<String> callToken = get(path, GATEWAY.service().mint().headers());
        assertThat(callToken.statusCode()).as("a token minted for calls").isEqualTo(401);
        assertThat(callToken.body()).contains("\"reason\":\"wrong_purpose\"");
        HttpResponse<String> policyDataToken = get(path, GATEWAY.service().use(TestGateway.USE_POLICY_DATA).mint().headers());
        assertThat(policyDataToken.body()).contains("\"reason\":\"wrong_purpose\"");
        TestGateway.Minted once = GATEWAY.manifestToken().mint();
        assertThat(get(path, once.headers()).statusCode()).isEqualTo(200);
        HttpResponse<String> replay = get(path, once.headers());
        assertThat(replay.statusCode()).as("the jti is one-time for this purpose too").isEqualTo(401);
        assertThat(replay.body()).contains("\"reason\":\"replayed_jti\"");
    }

    @Test
    void theOldManifestRouteIsGone() throws Exception {
        assertThat(get("/mcp-manifest", GATEWAY.manifestToken().mint().headers()).statusCode())
                .as("a token of the right purpose for another path is refused before the path is looked at").isEqualTo(401);
        assertThat(get("/mcp-manifest", GATEWAY.service().mint().headers()).statusCode())
                .as("the service variant is for the well-known paths and the protocol only").isEqualTo(401);
        assertThat(get("/mcp-manifest", GATEWAY.user().mint().headers()).statusCode()).as("nothing serves it").isEqualTo(404);
    }

    private static JsonNode tool(JsonNode manifest, String name) {
        for (JsonNode tool : manifest.get("tools")) {
            if (name.equals(tool.get("name").asString())) {
                return tool;
            }
        }
        throw new AssertionError("no tool " + name);
    }

    @Test
    void anAdditionalGroupIsInTheEntryAndInItsHashAndOnlyThere() throws Exception {
        JsonNode manifest = manifest();
        JsonNode ops = tool(manifest, "ops_report");

        assertThat(ops.get("additionalGroup").asString()).isEqualTo("mcp-jira-ops");
        assertThat(tool(manifest, "get_issue").has("additionalGroup")).isFalse();
        assertThat(pl.aiwcorpo.mcp.manifest.DefinitionHash.ofManifestTool(manifest.toString(), "ops_report"))
                .isEqualTo(ops.get("definitionHash").asString());
        tools.jackson.databind.node.ObjectNode without = ((tools.jackson.databind.node.ObjectNode) ops.deepCopy());
        without.remove("additionalGroup");
        assertThat(pl.aiwcorpo.mcp.manifest.DefinitionHash.compute(ops.get("definition").toString(), without.toString(), "jira",
                manifest.get("credential").toString())).as("the group is part of definitionHash")
                .isNotEqualTo(ops.get("definitionHash").asString());
    }
}
