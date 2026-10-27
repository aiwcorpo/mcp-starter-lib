package pl.aiwcorpo.mcp.platform.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.lang.Nullable;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** S-M1: a limit at every level of the input schema, for what the annotations describe and for hand-written schemas. */
class BoundedSchemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Filter(
            @ArgumentLimits(minLength = 1, maxLength = 40) @Nullable String status,
            @ArgumentLimits(maxItems = 5, maxLength = 30, pattern = "^[a-z-]+$") @Nullable List<String> labels,
            @ArgumentLimits(minimum = 0, maximum = 1000) @Nullable Integer page) {
    }

    record Field(@ArgumentLimits(maxLength = 20) String name, @ArgumentLimits(maxLength = 200) String value) {
    }

    static class Nested {
        @Tool(name = "search", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String search(@ArgumentLimits(maxLength = 100) String query, Filter filter,
                             @ArgumentLimits(maxItems = 10) List<Field> fields,
                             @ArgumentLimits(minimum = 1, maximum = 50) int limit) {
            return query;
        }
    }

    static class UnboundedRecordField {
        record Open(String text) {
        }

        @Tool(name = "t", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String t(Open open) {
            return "";
        }
    }

    static class UnboundedList {
        @Tool(name = "t", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String t(@ArgumentLimits(maxLength = 10) List<String> labels) {
            return "";
        }
    }

    static class UnboundedNumber {
        @Tool(name = "t", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String t(int limit) {
            return "";
        }
    }

    static class MapArgument {
        @Tool(name = "t", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String t(@ArgumentLimits(maxItems = 3, maxLength = 5) Map<String, String> fields) {
            return "";
        }
    }

    static class PlainObject {
        @Tool(name = "t", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String t(Object any) {
            return "";
        }
    }

    private static JsonNode schemaOf(Object tools) {
        ToolCallback callback = SchemaLimitedToolCallbacks.of(tools).getToolCallbacks()[0];
        ToolInputValidator.compile(callback.getToolDefinition(), ToolInputPolicy.STRICT);
        return JSON.readTree(callback.getToolDefinition().inputSchema());
    }

    private static ToolInputValidator validatorOf(Object tools) {
        return ToolInputValidator.compile(SchemaLimitedToolCallbacks.of(tools).getToolCallbacks()[0].getToolDefinition(),
                ToolInputPolicy.STRICT);
    }

    @Test
    void recordComponentsListsAndNumbersCarryTheirLimitsAndPassTheStartupRule() {
        JsonNode properties = schemaOf(new Nested()).path("properties");

        JsonNode filter = properties.path("filter");
        assertThat(filter.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(filter.path("required").isMissingNode() || !filter.path("required").toString().contains("status"))
                .as("an optional component is not required").isTrue();
        assertThat(filter.path("properties").path("status").path("maxLength").asInt()).isEqualTo(40);
        assertThat(filter.path("properties").path("labels").path("maxItems").asInt()).isEqualTo(5);
        assertThat(filter.path("properties").path("labels").path("items").path("maxLength").asInt()).isEqualTo(30);
        assertThat(filter.path("properties").path("page").path("maximum").asInt()).isEqualTo(1000);
        JsonNode fields = properties.path("fields");
        assertThat(fields.path("maxItems").asInt()).isEqualTo(10);
        assertThat(fields.path("items").path("properties").path("value").path("maxLength").asInt()).isEqualTo(200);
        assertThat(properties.path("limit").path("minimum").asInt()).isEqualTo(1);
    }

    @Test
    void theLimitsOfNestedLevelsAreEnforcedOnEveryCall() {
        ToolInputValidator validator = validatorOf(new Nested());
        String ok = "{\"query\":\"q\",\"filter\":{\"status\":\"open\"},\"fields\":[{\"name\":\"n\",\"value\":\"v\"}],\"limit\":5}";
        validator.validate(ok);

        String longText = "x".repeat(201);
        for (String bad : new String[] {
                "{\"query\":\"q\",\"filter\":{\"status\":\"" + "s".repeat(41) + "\"},\"fields\":[],\"limit\":5}",
                "{\"query\":\"q\",\"filter\":{\"labels\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]},\"fields\":[],\"limit\":5}",
                "{\"query\":\"q\",\"filter\":{\"labels\":[\"UPPER\"]},\"fields\":[],\"limit\":5}",
                "{\"query\":\"q\",\"filter\":{\"page\":1e30},\"fields\":[],\"limit\":5}",
                "{\"query\":\"q\",\"filter\":{\"extra\":1},\"fields\":[],\"limit\":5}",
                "{\"query\":\"q\",\"filter\":{},\"fields\":[{\"name\":\"n\",\"value\":\"" + longText + "\"}],\"limit\":5}",
                "{\"query\":\"q\",\"filter\":{},\"fields\":[{\"name\":\"n\",\"value\":\"v\",\"x\":1}],\"limit\":5}",
                "{\"query\":\"q\",\"filter\":{},\"fields\":[],\"limit\":51}"}) {
            assertThatThrownBy(() -> validator.validate(bad)).as(bad.substring(0, Math.min(90, bad.length())))
                    .isInstanceOf(InvalidToolArgumentException.class);
        }
    }

    @Test
    void aLevelWithoutALimitStopsTheServer() {
        assertThatThrownBy(() -> SchemaLimitedToolCallbacks.of(new UnboundedRecordField()))
                .hasMessageContaining("t(open).text").hasMessageContaining("maxLength");
        assertThatThrownBy(() -> SchemaLimitedToolCallbacks.of(new UnboundedList()))
                .hasMessageContaining("maxItems");
        assertThatThrownBy(() -> SchemaLimitedToolCallbacks.of(new UnboundedNumber()))
                .hasMessageContaining("minimum").hasMessageContaining("maximum");
    }

    @Test
    void mapsAndFreeFormObjectsAreRefused() {
        assertThatThrownBy(() -> SchemaLimitedToolCallbacks.of(new MapArgument())).hasMessageContaining("Map");
        assertThatThrownBy(() -> SchemaLimitedToolCallbacks.of(new PlainObject())).hasMessageContaining("bounded");
    }

    private static ToolInputValidator handWritten(String schema, ToolInputPolicy policy) {
        return ToolInputValidator.compile(
                DefaultToolDefinition.builder().name("hand_written").description("d").inputSchema(schema).build(), policy);
    }

    private static void refused(String schema, String... fragments) {
        assertThatThrownBy(() -> handWritten(schema, ToolInputPolicy.STRICT)).isInstanceOf(IllegalStateException.class)
                .hasMessageContainingAll(fragments);
    }

    @Test
    void handWrittenSchemasFollowTheSameRuleAtEveryDepth() {
        refused("{\"type\":\"object\",\"properties\":{\"f\":{\"type\":\"object\",\"properties\":{\"s\":{\"type\":\"string\","
                + "\"maxLength\":5}}}},\"additionalProperties\":false}", "f: accepts undeclared properties");
        refused("{\"type\":\"object\",\"properties\":{\"m\":{\"type\":\"object\"}},\"additionalProperties\":false}",
                "m: accepts undeclared properties");
        refused("{\"type\":\"object\",\"properties\":{\"l\":{\"type\":\"array\",\"items\":{\"type\":\"string\","
                + "\"maxLength\":5}}},\"additionalProperties\":false}", "l: a list needs maxItems");
        refused("{\"type\":\"object\",\"properties\":{\"l\":{\"type\":\"array\",\"maxItems\":3}},"
                + "\"additionalProperties\":false}", "l: a list needs an items schema");
        refused("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":0}},"
                + "\"additionalProperties\":false}", "n: a number needs minimum and maximum");
        refused("{\"type\":\"object\",\"properties\":{\"x\":{}},\"additionalProperties\":false}", "x: has no type");
        refused("{\"type\":\"object\",\"properties\":{\"s\":{\"type\":[\"string\",\"null\"]}},"
                + "\"additionalProperties\":false}", "s: text needs maxLength");
        refused("{\"type\":\"object\",\"properties\":{\"r\":{\"$ref\":\"#/$defs/R\"}},\"additionalProperties\":false,"
                + "\"$defs\":{\"R\":{\"type\":\"object\",\"properties\":{\"t\":{\"type\":\"string\"}},"
                + "\"additionalProperties\":false}}}", "r.t: text needs maxLength");
    }

    @Test
    void aTypedMapIsAllowedOnlyWithEveryBoundStated() {
        String map = "{\"type\":\"object\",\"properties\":{\"m\":{\"type\":\"object\",\"maxProperties\":5,"
                + "\"propertyNames\":{\"maxLength\":20},\"additionalProperties\":{\"type\":\"string\",\"maxLength\":50}}},"
                + "\"additionalProperties\":false}";
        handWritten(map, ToolInputPolicy.STRICT).validate("{\"m\":{\"a\":\"b\"}}");
        assertThatThrownBy(() -> handWritten(map, ToolInputPolicy.STRICT).validate("{\"m\":{\"a\":\"" + "x".repeat(51) + "\"}}"))
                .isInstanceOf(InvalidToolArgumentException.class);
        refused(map.replace("\"maxProperties\":5,", ""), "m: a map needs maxProperties");
        refused(map.replace("\"propertyNames\":{\"maxLength\":20},", ""), "m: a map needs propertyNames.maxLength");
        refused(map.replace("\"maxLength\":50", "\"x\":1"), "m{value}: text needs maxLength");
    }

    @Test
    void patternsMustBeAnchoredAndKeepAlternationInsideAGroup() {
        String template = "{\"type\":\"object\",\"properties\":{\"s\":{\"type\":\"string\",\"maxLength\":9,\"pattern\":%s}},"
                + "\"additionalProperties\":false}";
        for (String pattern : new String[] {"\"[0-9]+\"", "\"^a|b$\"", "\"^[0-9]+\"", "\"[0-9]+$\"", "\"^a$|^b$\""}) {
            refused(template.formatted(pattern), "s: pattern");
        }
        for (String pattern : new String[] {"\"^[0-9]+$\"", "\"^(?:a|b)$\"", "\"^[a|b]+$\"", "\"^a\\\\|b$\""}) {
            handWritten(template.formatted(pattern), ToolInputPolicy.STRICT);
        }
    }

    @Test
    void devOnlyWarnsAndStillEnforcesTheSchema() {
        ToolInputValidator open = handWritten("{\"type\":\"object\",\"properties\":{\"l\":{\"type\":\"array\","
                + "\"items\":{\"type\":\"string\",\"maxLength\":3}}},\"additionalProperties\":false}", ToolInputPolicy.DEV);

        open.validate("{\"l\":[\"a\"]}");
        assertThatThrownBy(() -> open.validate("{\"l\":[\"abcd\"]}")).isInstanceOf(InvalidToolArgumentException.class);
    }

    @Test
    void elementsPastAPrefixNeedALimitToo() {
        String template = "{\"type\":\"object\",\"properties\":{\"l\":{\"type\":\"array\",\"maxItems\":3,"
                + "\"prefixItems\":[{\"type\":\"string\",\"maxLength\":5}]%s}},\"additionalProperties\":false}";
        refused(template.formatted(""), "l: a list needs an items schema");
        refused(template.formatted(",\"items\":{\"type\":\"string\"}"), "l[]: text needs maxLength");
        handWritten(template.formatted(",\"items\":false"), ToolInputPolicy.STRICT);
        handWritten(template.formatted(",\"items\":{\"type\":\"string\",\"maxLength\":5}"), ToolInputPolicy.STRICT);
    }

    @Test
    void lineBreaksAreJudgedByTheRuleOfTheValueTheySitIn() {
        ToolInputValidator validator = handWritten("""
                {"type":"object","properties":{
                  "note":{"type":"string","maxLength":100},
                  "filter":{"type":"object","additionalProperties":false,"properties":{
                    "key":{"type":"string","maxLength":20,"pattern":"^[^/]+$"},
                    "text":{"type":"string","maxLength":100},
                    "keys":{"type":"array","maxItems":3,"items":{"type":"string","maxLength":20,"pattern":"^[^/]+$"}},
                    "ref":{"$ref":"#/$defs/Key"}}}},
                 "additionalProperties":false,
                 "$defs":{"Key":{"type":"string","maxLength":20,"pattern":"^[^/]+$"}}}""", ToolInputPolicy.STRICT);

        validator.validate("{\"note\":\"a\\nb\",\"filter\":{\"text\":\"a\\nb\\tc\",\"key\":\"a b\"}}");
        for (String bad : new String[] {
                "{\"filter\":{\"key\":\"a\\nb\"}}",
                "{\"filter\":{\"keys\":[\"ok\",\"a\\rb\"]}}",
                "{\"filter\":{\"ref\":\"a\\tb\"}}",
                "{\"filter\":{\"key\":\"a\\u200bb\"}}"}) {
            assertThatThrownBy(() -> validator.validate(bad)).as(bad).isInstanceOf(InvalidToolArgumentException.class);
        }
    }

    @Test
    void keysOfObjectsAreIdentifiersAndGoThroughTheSameHygiene() {
        ToolInputValidator validator = handWritten("""
                {"type":"object","properties":{
                  "labels":{"type":"object","maxProperties":3,"propertyNames":{"maxLength":20},
                            "additionalProperties":{"type":"string","maxLength":20}}},
                 "additionalProperties":false}""", ToolInputPolicy.STRICT);

        validator.validate("{\"labels\":{\"team\":\"a\",\"zone-1\":\"b\"}}");
        for (String key : new String[] {"a\\u202eb", "a\\nb", "a\\u200bb", "a\\u001bb", "a\\u200db", "a\\ud800b"}) {
            String json = "{\"labels\":{\"" + key + "\":\"v\"}}";
            assertThatThrownBy(() -> validator.validate(json)).as(key).isInstanceOf(InvalidToolArgumentException.class)
                    .hasMessageContaining("labels has a name with");
        }
    }

    @Test
    void anUndeclaredTopLevelNameWithHiddenCharactersIsRefusedWithoutBeingEchoed() {
        ToolInputValidator validator = handWritten("{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\","
                + "\"maxLength\":5}},\"additionalProperties\":false}", ToolInputPolicy.STRICT);

        assertThatThrownBy(() -> validator.validate("{\"x\\u202ey\":\"v\"}"))
                .isInstanceOf(InvalidToolArgumentException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("x").doesNotContain("\u202e"));
    }

    @Test
    void eachPrefixElementIsJudgedByItsOwnRuleAndTheRestByItems() {
        ToolInputValidator validator = handWritten("""
                {"type":"object","properties":{
                  "pair":{"type":"array","maxItems":3,
                          "prefixItems":[{"type":"string","maxLength":20,"pattern":"^[^/]+$"}],
                          "items":{"type":"string","maxLength":50}}},
                 "additionalProperties":false}""", ToolInputPolicy.STRICT);

        validator.validate("{\"pair\":[\"key\",\"free\\ntext\\tallowed here\"]}");
        for (String bad : new String[] {
                "{\"pair\":[\"a\\nb\"]}",
                "{\"pair\":[\"a\\u200db\"]}",
                "{\"pair\":[\"ok\",\"x\\u200by\"]}",
                "{\"pair\":[\"ok\",\"" + "z".repeat(51) + "\"]}"}) {
            assertThatThrownBy(() -> validator.validate(bad)).as(bad).isInstanceOf(InvalidToolArgumentException.class);
        }
    }

    @Test
    void anUnboundedPrefixElementStopsTheServer() {
        String template = "{\"type\":\"object\",\"properties\":{\"l\":{\"type\":\"array\",\"maxItems\":2,"
                + "\"prefixItems\":[%s],\"items\":false}},\"additionalProperties\":false}";
        refused(template.formatted("{\"type\":\"string\"}"), "l[]: text needs maxLength");
        refused(template.formatted("{\"type\":\"integer\",\"minimum\":0}"), "l[]: a number needs minimum and maximum");
        handWritten(template.formatted("{\"type\":\"string\",\"maxLength\":5}"), ToolInputPolicy.STRICT);
    }

    @Test
    void invisibleAndDirectionCharactersAreRefusedInFreeTextToo() {
        ToolInputValidator validator = handWritten("{\"type\":\"object\",\"properties\":{\"body\":{\"type\":\"string\","
                + "\"maxLength\":100}},\"additionalProperties\":false}", ToolInputPolicy.STRICT);

        validator.validate("{\"body\":\"family \\u200d emoji, mark \\u200f, text\"}");
        for (String hidden : new String[] {"\\u2028", "\\u2029", "\\u202e", "\\u2066", "\\u200b", "\\u2060", "\\ufeff",
                "\\ufffe", "\\uffff", "\\udb40\\udc41", "\\ud800", "\\u0085"}) {
            assertThatThrownBy(() -> validator.validate("{\"body\":\"a" + hidden + "b\"}")).as(hidden)
                    .isInstanceOf(InvalidToolArgumentException.class);
        }
    }

    @Test
    void combinatorsAreRefusedBecauseALimitMustBeReadableAtEachLevel() {
        for (String keyword : new String[] {"allOf", "anyOf", "oneOf"}) {
            refused("{\"type\":\"object\",\"properties\":{\"s\":{\"" + keyword + "\":[{\"type\":\"string\",\"maxLength\":5}]}},"
                    + "\"additionalProperties\":false}", "s: " + keyword + " is not supported");
        }
    }

    @Test
    void aPatternBesideARefAndOneInsideItBothDecideWhetherLineBreaksAreAllowed() {
        ToolInputValidator validator = handWritten("""
                {"type":"object","properties":{
                  "beside":{"$ref":"#/$defs/Free","pattern":"^[^/]+$"},
                  "inside":{"$ref":"#/$defs/Formatted"},
                  "free":{"$ref":"#/$defs/Free"}},
                 "additionalProperties":false,
                 "$defs":{"Free":{"type":"string","maxLength":20},
                          "Formatted":{"type":"string","maxLength":20,"pattern":"^[^/]+$"}}}""", ToolInputPolicy.DEV);

        validator.validate("{\"free\":\"a\\nb\"}");
        for (String property : new String[] {"beside", "inside"}) {
            assertThatThrownBy(() -> validator.validate("{\"" + property + "\":\"a\\nb\"}")).as(property)
                    .isInstanceOf(InvalidToolArgumentException.class);
        }
    }

    @Test
    void aCombinatorNodeIsJudgedAsFormattedWhenTheSchemaIsOnlyWarnedAboutInDev() {
        ToolInputValidator validator = handWritten("{\"type\":\"object\",\"properties\":{\"s\":{\"anyOf\":"
                + "[{\"type\":\"string\",\"maxLength\":20,\"pattern\":\"^[^/]+$\"},{\"type\":\"string\",\"maxLength\":5}]}},"
                + "\"additionalProperties\":false}", ToolInputPolicy.DEV);

        validator.validate("{\"s\":\"a b\"}");
        assertThatThrownBy(() -> validator.validate("{\"s\":\"a\\nb\"}")).isInstanceOf(InvalidToolArgumentException.class);
    }
}
