package pl.aiwcorpo.mcp.platform.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.lang.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ToolOutputSchemasTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    record Comment(String author, UntrustedText body) {
    }

    record Issue(String key, UntrustedText summary, @Nullable UntrustedText description, List<Comment> comments) {
    }

    static class Tools {
        Issue issue() {
            return null;
        }

        String plain() {
            return "";
        }

        List<UntrustedText> list() {
            return List.of();
        }
    }

    private static Method method(String name) throws NoSuchMethodException {
        return Tools.class.getDeclaredMethod(name);
    }

    @Test
    void aResultWithUntrustedTextGetsASchemaWithTheContractsDefinition() throws Exception {
        JsonNode schema = ToolOutputSchemas.forMethod(method("issue")).orElseThrow();

        assertThat(schema.path("type").asString()).isEqualTo("object");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        assertThat(schema.path("required").toString()).contains("summary", "comments").doesNotContain("description");
        assertThat(schema.at("/properties/summary/properties/kind/const").asString()).isEqualTo("untrusted-data");
        assertThat(schema.at("/properties/comments/items/properties/body/properties/format/enum").toString())
                .contains("text/plain", "text/markdown");
    }

    @Test
    void theSchemaAcceptsWhatTheTypesSerializeTo() throws Exception {
        JsonNode schema = ToolOutputSchemas.forMethod(method("issue")).orElseThrow();
        Issue issue = new Issue("ABC-1", UntrustedText.plain("title", 100), null,
                List.of(new Comment("jk", UntrustedText.markdown("hi", 10))));

        var errors = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12).getSchema(schema)
                .validate(JSON.valueToTree(issue));

        assertThat(errors).as(errors.toString()).isEmpty();
    }

    @Test
    void aResultWithoutUntrustedTextGetsNone() throws Exception {
        assertThat(ToolOutputSchemas.forMethod(method("plain"))).isEmpty();
    }

    @Test
    void aNonRecordResultWithUntrustedTextStopsTheServer() {
        assertThatThrownBy(() -> ToolOutputSchemas.forMethod(method("list"))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a record");
    }
}
