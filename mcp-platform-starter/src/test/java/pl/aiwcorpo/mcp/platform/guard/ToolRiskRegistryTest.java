package pl.aiwcorpo.mcp.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import pl.aiwcorpo.mcp.platform.input.ArgumentLimits;
import pl.aiwcorpo.mcp.platform.input.SchemaLimitedToolCallbacks;

/** What a tool must declare for the manifest to say the truth about it: risk, action, title (CC-115). */
class ToolRiskRegistryTest {

    private final ToolRiskRegistry registry = new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL);

    static class Tools {
        @Tool(name = "get_issue", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Get issue")
        public String get(@ArgumentLimits(maxLength = 10) String key) {
            return key;
        }

        @Tool(name = "rename_issue", description = "d")
        @ToolRisk(RiskLevel.UPDATE)
        @ToolTitle("Rename issue")
        public String rename(@ArgumentLimits(maxLength = 10) String key) {
            return key;
        }

        @Tool(name = "erase_issue", description = "d")
        @ToolRisk(RiskLevel.DESTRUCTIVE)
        @ToolTitle("Erase issue")
        public String erase(@ArgumentLimits(maxLength = 10) String key) {
            return key;
        }

        @Tool(name = "grant_access", description = "d")
        @ToolRisk(value = RiskLevel.DESTRUCTIVE, action = ToolAction.ADMIN)
        @ToolTitle("Grant access")
        public String grant(@ArgumentLimits(maxLength = 10) String key) {
            return key;
        }
    }

    static class NoTitle {
        @Tool(name = "get_issue", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        public String get(@ArgumentLimits(maxLength = 10) String key) {
            return key;
        }
    }

    static class BadTitles {
        @Tool(name = "get_issue", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle(" ")
        public String blank() {
            return "";
        }

        @Tool(name = "get_page", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Get\u200b page")
        public String hidden() {
            return "";
        }

        @Tool(name = "get_space", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Get space\n")
        public String lineBreak() {
            return "";
        }
    }

    static class ReadingDeleteAction {
        @Tool(name = "list_things", description = "d")
        @ToolRisk(value = RiskLevel.READ_ONLY, action = ToolAction.DELETE)
        @ToolTitle("List things")
        public String list() {
            return "";
        }
    }

    static class DestructiveReading {
        @Tool(name = "purge_things", description = "d")
        @ToolRisk(value = RiskLevel.DESTRUCTIVE, action = ToolAction.READ)
        @ToolTitle("Purge things")
        public String purge() {
            return "";
        }
    }

    static class UpdateDeleting {
        @Tool(name = "rename_things", description = "d")
        @ToolRisk(value = RiskLevel.UPDATE, action = ToolAction.DELETE)
        @ToolTitle("Rename things")
        public String rename() {
            return "";
        }
    }

    static class Confirmation {
        @Tool(name = "close_issue", description = "d")
        @ToolRisk(RiskLevel.UPDATE)
        @ToolTitle("Close issue")
        public String close(@ArgumentLimits(maxLength = 6) String confirmation_code) {
            return "";
        }
    }

    private void registerAll(Object tools) {
        for (ToolCallback callback : SchemaLimitedToolCallbacks.of(tools).getToolCallbacks()) {
            registry.register(callback);
        }
    }

    @Test
    void riskActionAndTitleComeFromTheAnnotationsAndNowhereElse() {
        registerAll(new Tools());

        assertThat(registry.riskOf("get_issue")).contains(RiskLevel.READ_ONLY);
        assertThat(registry.actionOf("get_issue")).contains(ToolAction.READ);
        assertThat(registry.actionOf("rename_issue")).contains(ToolAction.UPDATE);
        assertThat(registry.riskOf("erase_issue")).contains(RiskLevel.DESTRUCTIVE);
        assertThat(registry.actionOf("erase_issue")).contains(ToolAction.DELETE);
        assertThat(registry.actionOf("grant_access")).contains(ToolAction.ADMIN);
        assertThat(registry.titleOf("erase_issue")).contains("Erase issue");
    }

    @Test
    void aToolWithoutATitleStopsTheServer() {
        assertThatThrownBy(() -> registerAll(new NoTitle())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'get_issue' has no title").hasMessageContaining("@ToolTitle");
    }

    @Test
    void aTitleWithBlankHiddenCharactersOrLineBreaksStopsTheServer() {
        assertThatThrownBy(() -> registerAll(new BadTitles())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("has no title");
        ToolCallback[] callbacks = SchemaLimitedToolCallbacks.of(new BadTitles()).getToolCallbacks();
        for (ToolCallback callback : callbacks) {
            if (!"get_issue".equals(callback.getToolDefinition().name())) {
                assertThatThrownBy(() -> registry.register(callback)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("the title must be at most");
            }
        }
    }

    @Test
    void anActionThatContradictsTheRiskStopsTheServer() {
        assertThatThrownBy(() -> registerAll(new ReadingDeleteAction())).hasMessageContaining("contradicts risk READ_ONLY");
        assertThatThrownBy(() -> registerAll(new DestructiveReading())).hasMessageContaining("contradicts risk DESTRUCTIVE");
        assertThatThrownBy(() -> registerAll(new UpdateDeleting())).hasMessageContaining("contradicts risk UPDATE");
    }

    @Test
    void anArgumentNamedConfirmationCodeStopsTheServer() {
        assertThatThrownBy(() -> registerAll(new Confirmation())).hasMessageContaining("confirmation_code")
                .hasMessageContaining("gateway");
    }

    @Test
    void aHandWrittenToolDeclaresTheSameThingsThroughTheInterface() {
        ToolCallback callback = new Hand("wipe_cache", RiskLevel.DESTRUCTIVE, null, "Wipe cache");

        registry.register(callback);

        assertThat(registry.riskOf("wipe_cache")).contains(RiskLevel.DESTRUCTIVE);
        assertThat(registry.actionOf("wipe_cache")).contains(ToolAction.DELETE);
        assertThatThrownBy(() -> registry.register(new Hand("list_cache", RiskLevel.READ_ONLY, null, null)))
                .hasMessageContaining("has no title");
    }

    @Test
    void theNameOfADestructiveToolCannotBeFlaggedMilder() {
        assertThatThrownBy(() -> registry.register(new Hand("delete_issue", RiskLevel.UPDATE, null, "Delete issue")))
                .hasMessageContaining("DESTRUCTIVE");
    }

    private record Hand(String name, RiskLevel riskLevel, ToolAction action, String title)
            implements ToolCallback, RiskClassifiedTool {

        @Override
        public ToolAction action() {
            return action == null ? ToolAction.AUTO : action;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name(name).description("d")
                    .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}").build();
        }

        @Override
        public String call(String toolInput) {
            return "";
        }
    }
}
