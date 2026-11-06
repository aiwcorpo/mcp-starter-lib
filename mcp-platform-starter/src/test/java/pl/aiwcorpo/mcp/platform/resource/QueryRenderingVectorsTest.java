package pl.aiwcorpo.mcp.platform.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import pl.aiwcorpo.mcp.cedar.entities.QueryConstraint;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;
import pl.aiwcorpo.mcp.platform.resource.StructuredQuery.Direction;
import pl.aiwcorpo.mcp.platform.resource.StructuredQuery.Operator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every vector of {@code test-vectors/query-rendering} (CC-160): the queries the starter renders for JQL, CQL and the OpenSearch
 * patterns, and the resolution of index names and arguments from the answer of {@code GET _resolve/index}.
 */
class QueryRenderingVectorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int EXPECTED_VECTORS = 26;

    record Vector(String name, JsonNode data) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> vectors() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mcp-contracts/test-vectors/query-rendering/*.json");
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
    void theStarterRendersWhatTheVectorSays(Vector vector) {
        JsonNode data = vector.data();
        JsonNode input = data.get("input");
        JsonNode expected = data.get("expected");
        String kind = data.get("kind").asString();
        if (expected.has("error") && "nothing_to_query_or_not_allowed".equals(expected.get("error").asString())) {
            assertThatThrownBy(() -> actual(kind, input)).isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
            return;
        }
        if (expected.has("error")) {
            assertThatThrownBy(() -> actual(kind, input)).satisfies(error -> assertThat(code(error)).isEqualTo(expected.get("error").asString()));
            return;
        }
        Object actual = actual(kind, input);
        if (expected.has("names")) {
            assertThat(actual).isEqualTo(strings(expected.get("names")));
        } else if (expected.get("query").isArray()) {
            assertThat(actual).isEqualTo(strings(expected.get("query")));
        } else {
            assertThat(actual).isEqualTo(expected.get("query").asString());
        }
    }

    private static Object actual(String kind, JsonNode input) {
        return switch (kind) {
            case "jql" -> {
                QueryScope scope = grants("Jira::Project", input);
                yield render(scope.jql(StructuredQuery.JQL_CONDITION_FIELDS, true), input);
            }
            case "cql" -> {
                QueryScope scope = grants("Confluence::Space", input);
                yield render(scope.cql(StructuredQuery.CQL_CONDITION_FIELDS, true), input);
            }
            case "opensearch-pattern" -> grants("OpenSearch::Index", input).opensearchIndexExpression();
            case "opensearch-resolve" -> grants("OpenSearch::Index", input).resolveIndexNames(input.get("resolved").toString());
            case "opensearch-argument" -> List.of(grants("OpenSearch::Index", input).resolveIndexArgument(
                    input.get("argument").asString(), input.get("resolved").toString()));
            default -> throw new AssertionError("unknown kind " + kind);
        };
    }

    private static QueryScope grants(String type, JsonNode input) {
        JsonNode ids = input.has("scope") ? input.get("scope") : input.get("granted");
        return QueryScope.of(new QueryConstraint(type, new TreeSet<>(strings(ids))), ExampleTypes.ALL);
    }

    private static String render(StructuredQuery.Builder builder, JsonNode input) {
        for (JsonNode condition : input.path("conditions")) {
            builder.where(condition.get("field").asString(), operator(condition.get("op").asString()), condition.get("value").asString());
        }
        if (input.has("orderBy")) {
            builder.orderBy(input.get("orderBy").get("field").asString(), Direction.valueOf(input.get("orderBy").get("direction").asString()));
        }
        return builder.build();
    }

    private static Operator operator(String text) {
        return switch (text) {
            case "=" -> Operator.EQUALS;
            case "~" -> Operator.CONTAINS;
            case "!=" -> Operator.NOT_EQUALS;
            default -> throw new IllegalArgumentException("operator " + text);
        };
    }

    private static String code(Throwable error) {
        if (error instanceof InvalidToolArgumentException) {
            return "invalid_argument";
        }
        if (error instanceof SanitizedToolException refused) {
            return refused.getCode();
        }
        throw new AssertionError("unexpected " + error, error);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }
}
