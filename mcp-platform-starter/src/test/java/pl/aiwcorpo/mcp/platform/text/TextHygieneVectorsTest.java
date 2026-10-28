package pl.aiwcorpo.mcp.platform.text;

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
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Every vector of {@code test-vectors/text-hygiene/} (CC-121, CC-122) against {@link TextHygiene}. */
class TextHygieneVectorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int EXPECTED_VECTORS = 79;

    record Vector(String name, JsonNode data) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> vectors() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mcp-contracts/test-vectors/text-hygiene/*.json");
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

    @Test
    void theWholeDirectoryIsRead() throws IOException {
        assertThat(vectors()).hasSize(EXPECTED_VECTORS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allVectors")
    void hygieneAgreesWithTheVector(Vector vector) {
        JsonNode data = vector.data();
        String mode = data.get("mode").asString();
        String input = data.get("input").asString();
        JsonNode expected = data.get("expected");
        if ("system-text".equals(mode)) {
            assertThat(TextHygiene.normalize(input)).isEqualTo(expected.get("output").asString());
            return;
        }
        TextHygiene.Problem problem = switch (mode) {
            case "argument" -> TextHygiene.firstProblem(input, false);
            case "argument-identifier" -> TextHygiene.firstProblem(input, true);
            case "argument-mention-filtered" -> TextHygiene.firstProblem(input, false, true);
            default -> throw new IllegalStateException("unknown mode " + mode);
        };
        assertThat(problem == null).isEqualTo(expected.get("valid").asBoolean());
        if (problem != null && expected.has("reason")) {
            assertThat(problem.reason()).isEqualTo(expected.get("reason").asString());
        }
    }
}
