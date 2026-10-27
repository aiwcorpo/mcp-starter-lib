package pl.aiwcorpo.mcp.platform.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallback;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SchemaLimitedToolCallbacksTest {

    static class Good {
        @Tool(name = "b_search", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String search(@ArgumentLimits(minLength = 2, maxLength = 20, pattern = "^[a-z]+$") String text,
                             @ArgumentLimits(minimum = 1, maximum = 50) Long size, boolean exact) {
            return text;
        }

        @Tool(name = "a_ping", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String ping() {
            return "pong";
        }
    }

    static class TextWithoutLimit {
        @Tool(name = "t", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String t(String text) {
            return text;
        }
    }

    static class NumericLimitOnText {
        @Tool(name = "t", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String t(@ArgumentLimits(maxLength = 5, maximum = 3) String text) {
            return text;
        }
    }

    static class TextLimitOnInteger {
        @Tool(name = "t", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String t(@ArgumentLimits(maxLength = 5) Long size) {
            return "";
        }
    }

    @Test
    void limitsAndAClosedObjectEndUpInTheSchemaAndToolsStayMethodCallbacks() {
        ToolCallback[] callbacks = SchemaLimitedToolCallbacks.of(new Good()).getToolCallbacks();

        assertThat(callbacks).extracting(c -> c.getToolDefinition().name()).containsExactly("a_ping", "b_search");
        assertThat(callbacks).allSatisfy(c -> assertThat(c).isInstanceOf(MethodToolCallback.class));
        JsonNode schema = JsonMapper.builder().build().readTree(callbacks[1].getToolDefinition().inputSchema());
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        JsonNode text = schema.path("properties").path("text");
        assertThat(text.path("minLength").asInt()).isEqualTo(2);
        assertThat(text.path("maxLength").asInt()).isEqualTo(20);
        assertThat(text.path("pattern").asString()).isEqualTo("^[a-z]+$");
        assertThat(schema.path("properties").path("size").path("minimum").asInt()).isEqualTo(1);
        assertThat(schema.path("properties").path("size").path("maximum").asInt()).isEqualTo(50);
        assertThat(schema.path("properties").path("exact").has("maxLength")).isFalse();
        JsonNode ping = JsonMapper.builder().build().readTree(callbacks[0].getToolDefinition().inputSchema());
        assertThat(ping.path("additionalProperties").asBoolean(true)).isFalse();
    }

    @Test
    void theGeneratedSchemasPassTheStartupPolicy() {
        for (ToolCallback callback : SchemaLimitedToolCallbacks.of(new Good()).getToolCallbacks()) {
            ToolInputValidator.compile(callback.getToolDefinition(), ToolInputPolicy.STRICT);
        }
    }

    @Test
    void textWithoutAMaxLengthStopsTheServer() {
        assertThatThrownBy(() -> SchemaLimitedToolCallbacks.of(new TextWithoutLimit()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("maxLength");
    }

    @Test
    void limitsOfTheWrongKindStopTheServer() {
        assertThatThrownBy(() -> SchemaLimitedToolCallbacks.of(new NumericLimitOnText()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("minimum/maximum on a text");
        assertThatThrownBy(() -> SchemaLimitedToolCallbacks.of(new TextLimitOnInteger()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("text limits only on text");
    }

    @Test
    void noToolMethodsIsAnError() {
        assertThatThrownBy(() -> SchemaLimitedToolCallbacks.of(new Object()))
                .isInstanceOf(IllegalStateException.class);
    }
}
