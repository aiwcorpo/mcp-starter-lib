package pl.aiwcorpo.mcp.platform.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import pl.aiwcorpo.mcp.platform.error.InvalidToolArgumentException;
import pl.aiwcorpo.mcp.platform.guard.RiskLevel;
import pl.aiwcorpo.mcp.platform.guard.ToolMethods;
import pl.aiwcorpo.mcp.platform.guard.ToolRisk;
import pl.aiwcorpo.mcp.platform.guard.ToolTitle;

/** Full width ASCII is refused only in the arguments a mention filter reads (CC-121). */
class MentionFilteredTest {

    public record Comment(@ArgumentLimits(maxLength = 200, mentionFiltered = true) String body,
                          @ArgumentLimits(maxLength = 40) String author) {
    }

    static class Tools {
        @Tool(name = "add_comment", description = "d")
        @ToolRisk(RiskLevel.UPDATE)
        @ToolTitle("Add comment")
        public String add(@ArgumentLimits(maxLength = 10) String key, Comment comment,
                          @ArgumentLimits(maxItems = 3, maxLength = 40, mentionFiltered = true) List<String> notes,
                          @ArgumentLimits(maxLength = 40) String summary) {
            return key;
        }
    }

    static class MentionOnNumber {
        @Tool(name = "bad", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Bad")
        public String bad(@ArgumentLimits(minimum = 1, maximum = 3, mentionFiltered = true) long n) {
            return "";
        }
    }

    private static final String FULL_WIDTH_AT_ALL = "\\uff20\\uff41\\uff4c\\uff4c";

    @Test
    void thePathsOfTheMarkedArgumentsAreFoundInParametersRecordsAndLists() {
        ToolCallback callback = SchemaLimitedToolCallbacks.of(new Tools()).getToolCallbacks()[0];

        assertThat(MentionFilteredArguments.of(ToolMethods.methodOf(callback).orElseThrow()))
                .containsExactlyInAnyOrder("comment.body", "notes[]");
    }

    @Test
    void fullWidthLettersAreRefusedInAMarkedArgumentAndAcceptedElsewhere() {
        ToolCallback callback = SchemaLimitedToolCallbacks.of(new Tools()).getToolCallbacks()[0];
        ToolInputValidator validator = ToolInputValidator.compile(callback.getToolDefinition(), ToolInputPolicy.STRICT,
                MentionFilteredArguments.of(ToolMethods.methodOf(callback).orElseThrow()));
        String template = "{\"key\":\"ABC-1\",\"comment\":{\"body\":\"%s\",\"author\":\"%s\"},\"notes\":[\"%s\"],\"summary\":\"%s\"}";

        validator.validate(template.formatted("@all", "a", "n", "s"));
        validator.validate(template.formatted("plain", FULL_WIDTH_AT_ALL, "n", FULL_WIDTH_AT_ALL));
        assertThatThrownBy(() -> validator.validate(template.formatted(FULL_WIDTH_AT_ALL, "a", "n", "s")))
                .isInstanceOf(InvalidToolArgumentException.class).hasMessageContaining("comment contains full width");
        assertThatThrownBy(() -> validator.validate(template.formatted("plain", "a", FULL_WIDTH_AT_ALL, "s")))
                .isInstanceOf(InvalidToolArgumentException.class).hasMessageContaining("notes contains full width");
    }

    @Test
    void theMarkOnANumberStopsTheServer() {
        assertThatThrownBy(() -> SchemaLimitedToolCallbacks.of(new MentionOnNumber()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("text limits only on text");
    }
}
