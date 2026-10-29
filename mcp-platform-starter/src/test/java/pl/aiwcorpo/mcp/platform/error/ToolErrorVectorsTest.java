package pl.aiwcorpo.mcp.platform.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import pl.aiwcorpo.mcp.platform.ContractSchemas;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every vector of {@code test-vectors/tool-errors}: the schema accepts the valid ones and refuses the invalid ones, and what the
 * mapper sends for a code the server maps is what the vector says, in particular the one text of {@code not_found_or_denied} (CC-146).
 */
class ToolErrorVectorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int EXPECTED_VECTORS = 33;

    record Vector(String name, JsonNode data) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> vectors() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mcp-contracts/test-vectors/tool-errors/*.json");
        List<Vector> vectors = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                vectors.add(new Vector(resource.getFilename(), JSON.readTree(in)));
            }
        }
        vectors.sort(Comparator.comparing(Vector::name));
        return vectors;
    }

    static Stream<Arguments> allVectors() throws IOException {
        return vectors().stream().map(Arguments::of);
    }

    /** The codes a server emits; the confirmation, step-up and rate-limit codes are the gateway's and carry fields the starter does not know. */
    private static final List<String> SERVER_CODES = List.of("access_denied", "credential_required", "not_found_or_denied",
            "invalid_argument", "internal_error", "upstream_unavailable", "unauthenticated");

    static Stream<Arguments> validVectors() throws IOException {
        return vectors().stream().filter(v -> v.data().at("/expected/valid").asBoolean())
                .filter(v -> SERVER_CODES.contains(v.data().at("/error/error").asString())).map(Arguments::of);
    }

    @Test
    void theWholeDirectoryIsRead() throws IOException {
        assertThat(vectors()).hasSize(EXPECTED_VECTORS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVectors")
    void theSchemaAgreesWithTheVector(Vector vector) {
        List<String> violations = ContractSchemas.violations(ContractSchemas.TOOL_ERROR, vector.data().get("error").toString());

        assertThat(violations.isEmpty()).as(violations.toString()).isEqualTo(vector.data().at("/expected/valid").asBoolean());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validVectors")
    void aServerMappingToTheVectorsCodeReachesTheClientAsTheVectorSays(Vector vector) {
        JsonNode error = vector.data().get("error");
        Map<String, Object> details = new LinkedHashMap<>(JSON.convertValue(error, new TypeReference<LinkedHashMap<String, Object>>() { }));
        String code = (String) details.remove("error");
        String message = (String) details.remove("message");
        ToolErrorMapping mapping = ex -> Optional.of(new McpErrorMapper.SafeError(code, "text the server chose", details));

        McpErrorMapper.SafeError sent = new McpErrorMapper(List.of(mapping), "https://mcp-portal.internal/connections?system={system}")
                .map(new IllegalStateException("x"));

        assertThat(sent.code()).isEqualTo(code);
        if ("not_found_or_denied".equals(code)) {
            assertThat(sent.message()).as("the one text, whatever the server wrote").isEqualTo(message);
        }
        assertThat(ContractSchemas.violations(ContractSchemas.TOOL_ERROR, JSON.writeValueAsString(asObject(sent)))).isEmpty();
    }

    private static Map<String, Object> asObject(McpErrorMapper.SafeError error) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("error", error.code());
        object.put("message", error.message());
        object.putAll(error.details());
        return object;
    }
}
