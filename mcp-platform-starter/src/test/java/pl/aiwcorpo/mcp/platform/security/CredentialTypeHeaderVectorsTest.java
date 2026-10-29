package pl.aiwcorpo.mcp.platform.security;

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

/** The server-side vectors of {@code test-vectors/credential-type-header} (CC-166); the gateway's belong to the gateway. */
class CredentialTypeHeaderVectorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Vector(String name, JsonNode data) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> vectors() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mcp-contracts/test-vectors/credential-type-header/*.json");
        List<Vector> vectors = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                vectors.add(new Vector(resource.getFilename(), JSON.readTree(in)));
            }
        }
        vectors.sort(Comparator.comparing(Vector::name));
        return vectors;
    }

    static Stream<Arguments> serverVectors() throws IOException {
        return vectors().stream().filter(v -> "server".equals(v.data().at("/input/side").asString())).map(Arguments::of);
    }

    @Test
    void theWholeDirectoryIsRead() throws IOException {
        assertThat(vectors()).hasSize(13);
        assertThat(serverVectors().count()).isEqualTo(9);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("serverVectors")
    void theCheckAgreesWithTheVector(Vector vector) {
        JsonNode input = vector.data().get("input");
        List<String> allowed = new ArrayList<>();
        input.get("allowed").forEach(type -> allowed.add(type.asString()));
        String header = input.get("header").isNull() ? null : input.get("header").asString();

        boolean admitted = CredentialTypeCheck.admits(input.get("credentialPresent").asBoolean(), header, allowed);

        assertThat(admitted).isEqualTo("continue".equals(vector.data().at("/expected/decision").asString()));
    }
}
