package pl.aiwcorpo.mcp.platform.input;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.lang.Nullable;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.guard.ToolTitle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The closed and bounded {@code inputSchema} of the contracts (CC-078, CC-123): the startup rule of the starter
 * agrees with the manifest vectors of mcp-contracts 3.0.1, and the schemas the starter generates for the argument
 * shapes servers use satisfy it, so the manifest of a server passes the gateway's rules.
 */
class ManifestInputSchemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Vector(String name, JsonNode data) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> vectors() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mcp-contracts/test-vectors/manifests/*.json");
        List<Vector> vectors = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                vectors.add(new Vector(resource.getFilename(), JSON.readTree(in)));
            }
        }
        vectors.sort(Comparator.comparing(Vector::name));
        return vectors;
    }

    static Stream<Arguments> schemaVectors() throws IOException {
        return vectors().stream().filter(v -> v.name().startsWith("invalid-input-schema-") || v.name().startsWith("valid-"))
                .map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("schemaVectors")
    void theStartupRuleAgreesWithTheManifestVector(Vector vector) {
        boolean valid = vector.data().get("expected").get("valid").asBoolean();
        List<String> problems = new ArrayList<>();
        for (JsonNode tool : vector.data().get("manifest").get("tools")) {
            problems.addAll(InputSchemaBounds.problems(tool.get("definition").get("inputSchema")));
        }

        assertThat(problems.isEmpty()).as(String.valueOf(problems)).isEqualTo(valid);
    }

    @Test
    void theOpenSearchIndexPrefixOfTheContractsIsPublishedAsItIsAndPassesTheAnchoringRule() {
        String published = SchemaLimitedToolCallbacks.contractDefinition("OpenSearchIndexPrefix").path("pattern").asString();

        assertThat(published).as("one anchored pattern since CC-139, no rewriting by the starter")
                .isEqualTo("^(?:[a-z0-9][a-z0-9._-]{0,253}[a-z0-9_]|[a-z0-9])$");
        assertThat(InputSchemaBounds.anchored(published)).isTrue();
        for (String prefix : List.of("a", "logs", "logs-team-a", "x_")) {
            assertThat(java.util.regex.Pattern.matches(published, prefix)).as(prefix).isTrue();
        }
        for (String prefix : List.of("", "-a", "logs-", ".hidden", "A")) {
            assertThat(java.util.regex.Pattern.matches(published, prefix)).as(prefix).isFalse();
        }
    }

    public record Person(@ArgumentLimits(maxLength = 40) String name, @Nullable @ArgumentLimits(maxLength = 80) String note) {
    }

    public record Page(@ArgumentLimits(minimum = 0, maximum = 1000) Integer offset,
                       @Nullable @ArgumentLimits(minimum = 1, maximum = 50) Integer size) {
    }

    public enum Kind { BUG, TASK }

    static class Shapes {
        @Tool(name = "shapes", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Shapes")
        public String shapes(@ArgumentLimits(maxLength = 16, contractPattern = "JiraIssueKey") String key,
                             @ArgumentLimits(maxLength = 40, mentionFiltered = true) String comment,
                             @ArgumentLimits(maxItems = 5, maxLength = 20) List<String> labels,
                             @ArgumentLimits(minimum = 1, maximum = 9) long priority,
                             @ArgumentLimits(minimum = 0, maximum = 1) double weight,
                             boolean dryRun, Kind kind, Person reporter, Person assignee,
                             @ArgumentLimits(maxItems = 3) List<Person> watchers, Page page,
                             @Nullable @ArgumentLimits(maxLength = 10) String optionalText,
                             @Nullable Kind optionalKind) {
            return key;
        }
    }

    @Test
    void theSchemaGeneratedForTheShapesServersUseIsClosedBoundedAndFlat() {
        ToolCallback callback = SchemaLimitedToolCallbacks.of(new Shapes()).getToolCallbacks()[0];
        JsonNode schema = JSON.readTree(callback.getToolDefinition().inputSchema());

        assertThat(InputSchemaBounds.problems(schema)).isEmpty();
        assertThat(schema.toString()).doesNotContain("$ref").doesNotContain("$defs").doesNotContain("\"null\"");
    }

    @Test
    void theDefinitionsServersReadFromTheContractsAreStillThere() {
        for (String definition : List.of("JiraProjectKey", "JiraIssueKey", "ConfluenceSpaceKey", "ConfluencePageId",
                "GitLabGroupPath", "GitLabProjectPath", "OpenSearchIndexName", "OpenSearchIndexPrefix",
                "ServiceDeskQueueId", "ServiceDeskTicketId")) {
            assertThat(SchemaLimitedToolCallbacks.contractDefinition(definition)).as(definition).isNotNull();
            assertThat(SchemaLimitedToolCallbacks.contractDefinition(definition).path("pattern").asString(null))
                    .as(definition).isNotNull();
            assertThat(SchemaLimitedToolCallbacks.contractDefinition(definition).path("type").asString(null))
                    .as(definition).isEqualTo("string");
        }
    }
}
