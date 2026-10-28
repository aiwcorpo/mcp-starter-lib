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
        HttpResponse<String> response = get("/mcp-manifest", GATEWAY.service().mint().headers());
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
    }

    @Test
    void theManifestFileIsWrittenForTheRegistrationStep() throws Exception {
        assertThat(JSON.readTree(Files.readString(FILE))).isEqualTo(manifest());
    }

    @Test
    void theManifestNeedsTheInternalJwt() throws Exception {
        assertThat(get("/mcp-manifest", Map.of()).statusCode()).isEqualTo(401);
        assertThat(get("/mcp-manifest", GATEWAY.user().tool("get_issue").mint().headers()).statusCode())
                .as("the manifest is not a tool call").isEqualTo(401);
    }

    private static JsonNode tool(JsonNode manifest, String name) {
        for (JsonNode tool : manifest.get("tools")) {
            if (name.equals(tool.get("name").asString())) {
                return tool;
            }
        }
        throw new AssertionError("no tool " + name);
    }
}
