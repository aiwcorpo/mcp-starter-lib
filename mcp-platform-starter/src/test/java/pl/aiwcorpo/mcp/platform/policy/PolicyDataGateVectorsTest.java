package pl.aiwcorpo.mcp.platform.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import pl.aiwcorpo.mcp.manifest.PolicyDataGate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every vector of {@code policy-data-gate/} of mcp-contracts: the gate (data as new as the token and approved for these policies), and
 * the client check of CC-266 ({@code azp} against {@code allowedClients} for a human, {@code allowedAgentClients} for a token with
 * {@code pty}). The vectors with {@code previous} give the revision the gateway assigns after an agent changes; that is the gateway's
 * rule, not a receiver's, and is counted below instead of run.
 */
class PolicyDataGateVectorsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int VECTORS = 21;
    private static final int GATEWAY_REVISION_RULE = 2;

    record Vector(String name, JsonNode data) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> vectors() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mcp-contracts/test-vectors/policy-data-gate/*.json");
        List<Vector> vectors = new ArrayList<>();
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                vectors.add(new Vector(resource.getFilename(), JSON.readTree(in)));
            }
        }
        vectors.sort(Comparator.comparing(Vector::name));
        return vectors;
    }

    static Stream<Arguments> receiverVectors() throws IOException {
        return vectors().stream().filter(v -> !v.data().get("input").has("previous")).map(Arguments::of);
    }

    @Test
    void theWholeDirectoryIsReadAndOnlyTheGatewaysRevisionRuleIsLeftOut() throws IOException {
        assertThat(vectors()).hasSize(VECTORS);
        assertThat(vectors().stream().filter(v -> v.data().get("input").has("previous"))).hasSize(GATEWAY_REVISION_RULE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("receiverVectors")
    void theStarterDecidesAsTheVectorSays(Vector vector) {
        JsonNode input = vector.data().get("input");
        JsonNode expected = vector.data().get("expected");
        if (input.has("allowedAgentClients")) {
            PolicyData data = new PolicyData(1, Optional.empty(), "[]", strings(input.get("allowedClients")), List.of(), List.of(),
                    strings(input.get("allowedAgentClients")));
            boolean allowed = data.allows(input.get("azp").asString(), input.has("pty") ? input.get("pty").asString() : null);

            assertThat(allowed).isEqualTo("continue".equals(expected.get("decision").asString()));
            if (!allowed) {
                assertThat(expected.get("reason").asString()).isEqualTo("client_not_allowed");
            }
            return;
        }
        JsonNode held = input.get("held");
        PolicyDataGate.Data data = held.isNull() ? null : new PolicyDataGate.Data(held.get("revision").asLong(),
                held.has("approvedPoliciesHash") ? Optional.of(held.get("approvedPoliciesHash").asString()) : Optional.empty(),
                held.get("overridesValid").asBoolean());
        OptionalLong prv = input.has("prv") && !input.get("prv").isNull() ? OptionalLong.of(input.get("prv").asLong()) : OptionalLong.empty();
        String method = input.has("method") ? input.get("method").asString() : "tools/call";

        PolicyDataGate.Decision decision = PolicyDataGate.decide(input.get("ownHash").asString(), data, prv, method);

        assertThat(decision.allowed()).isEqualTo("continue".equals(expected.get("decision").asString()));
        if (!decision.allowed()) {
            assertThat(decision.reason()).isEqualTo(expected.get("reason").asString());
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(v -> values.add(v.asString()));
        return values;
    }
}
