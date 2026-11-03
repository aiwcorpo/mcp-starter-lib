package pl.aiwcorpo.mcp.platform.paging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.text.ToolOutputSchemas;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;
import tools.jackson.databind.JsonNode;

class PagedResultTest {

    record Page(String key, UntrustedText summary) {
    }

    static class Tools {
        PagedResult<Page> search() {
            return null;
        }
    }

    @Test
    void theLastPageHasNoCursorAndNothingCut() {
        PagedResult<String> page = PagedResult.of(List.of("a"), null);

        assertThat(page.nextCursor()).isNull();
        assertThat(page.truncated()).isFalse();
        assertThat(page.omitted()).isEmpty();
    }

    @Test
    void omittedCodesAreFixedSnakeCaseAndDistinct() {
        assertThat(new PagedResult<>(List.of(), null, true, List.of("binary_attachment", "unsupported_macro")).omitted())
                .hasSize(2);
        for (List<String> bad : List.of(List.of("Binary"), List.of("a b"), List.of("1abc"), List.of(""),
                List.of("x", "x"), List.of("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10", "a11"),
                List.of("a".repeat(41)))) {
            assertThatThrownBy(() -> new PagedResult<>(List.of(), null, false, bad)).as(bad.toString())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void aCursorLongerThanTheArgumentLimitIsRefused() {
        assertThatThrownBy(() -> PagedResult.of(List.of(), "x".repeat(CursorCodec.MAX_CURSOR_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theOutputSchemaOfAGenericResultDescribesTheElementsAndKeepsTheCursorOptional() throws Exception {
        Method method = Tools.class.getDeclaredMethod("search");

        JsonNode schema = ToolOutputSchemas.forMethod(method).orElseThrow();

        assertThat(schema.at("/properties/items/items/properties/summary/properties/kind/const").asString())
                .isEqualTo("untrusted-data");
        assertThat(schema.path("required").toString()).contains("items", "truncated", "omitted")
                .doesNotContain("nextCursor");
        assertThat(schema.at("/properties/nextCursor/anyOf").toString()).contains("string", "null");
        assertThat(schema.at("/properties/omitted/items/type").asString()).isEqualTo("string");
    }
}
