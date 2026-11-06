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
        @pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources
        public String get(@ArgumentLimits(maxLength = 10) String key) {
            return key;
        }

        @Tool(name = "rename_issue", description = "d")
        @ToolRisk(RiskLevel.UPDATE)
        @ToolTitle("Rename issue")
        @pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources
        public String rename(@ArgumentLimits(maxLength = 10) String key) {
            return key;
        }

        @Tool(name = "erase_issue", description = "d")
        @ToolRisk(RiskLevel.DESTRUCTIVE)
        @ToolTitle("Erase issue")
        @pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources
        public String erase(@ArgumentLimits(maxLength = 10) String key) {
            return key;
        }

        @Tool(name = "grant_access", description = "d")
        @ToolRisk(value = RiskLevel.DESTRUCTIVE, action = ToolAction.ADMIN)
        @ToolTitle("Grant access")
        @pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources
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
        @pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources
        public String blank() {
            return "";
        }

        @Tool(name = "get_page", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Get\u200b page")
        @pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources
        public String hidden() {
            return "";
        }

        @Tool(name = "get_space", description = "d")
        @ToolRisk(RiskLevel.READ_ONLY)
        @ToolTitle("Get space\n")
        @pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources
        public String lineBreak() {
            return "";
        }
    }

    static class ReadingDeleteAction {
        @Tool(name = "list_things", description = "d")
        @ToolRisk(value = RiskLevel.READ_ONLY, action = ToolAction.DELETE)
        @ToolTitle("List things")
        @pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources
        public String list() {
            return "";
        }
    }

    static class DestructiveReading {
        @Tool(name = "purge_things", description = "d")
        @ToolRisk(value = RiskLevel.DESTRUCTIVE, action = ToolAction.READ)
        @ToolTitle("Purge things")
        @pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources
        public String purge() {
            return "";
        }
    }

    static class UpdateDeleting {
        @Tool(name = "rename_things", description = "d")
        @ToolRisk(value = RiskLevel.UPDATE, action = ToolAction.DELETE)
        @ToolTitle("Rename things")
        @pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources
        public String rename() {
            return "";
        }
    }

    static class Confirmation {
        @Tool(name = "close_issue", description = "d")
        @ToolRisk(RiskLevel.UPDATE)
        @ToolTitle("Close issue")
        @pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources
        public String close(@ArgumentLimits(maxLength = 6) String confirmation_code) {
            return "";
        }
    }

    static class OpsOnly {
        @Tool(name = "ops_report", description = "d")
        @ToolRisk(value = RiskLevel.READ_ONLY, additionalGroup = "mcp-jira-ops")
        @ToolTitle("Operations report")
        @pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources
        public String report() {
            return "";
        }
    }

    static class BadAdditionalGroup {
        @Tool(name = "ops_report", description = "d")
        @ToolRisk(value = RiskLevel.READ_ONLY, additionalGroup = "mcp-jira-write")
        @ToolTitle("Operations report")
        @pl.aiwcorpo.mcp.platform.resource.ResultWithoutResources
        public String report() {
            return "";
        }
    }

    @Test
    void anAdditionalGroupIsRecordedAndOneThatIsARiskGroupOrMalformedStopsTheServer() {
        registerAll(new OpsOnly());
        assertThat(registry.additionalGroupOf("ops_report")).contains("mcp-jira-ops");
        assertThat(new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL).additionalGroupOf("nothing")).isEmpty();

        ToolRiskRegistry fresh = new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL);
        assertThatThrownBy(() -> fresh.register(SchemaLimitedToolCallbacks.of(new BadAdditionalGroup()).getToolCallbacks()[0]))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("additionalGroup 'mcp-jira-write'");
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
    void theShortAndRareWordsOfDestructionAreCaughtToo() {
        for (String name : new String[] {"del_issue", "rm_branch", "revoke_token", "unlink_page", "kill_job",
                "terminate_instance", "reset_password", "clear_cache"}) {
            assertThatThrownBy(() -> registry.register(new Hand(name, RiskLevel.UPDATE, null, "Title")))
                    .as(name).hasMessageContaining("DESTRUCTIVE");
        }
        registry.register(new Hand("clear_cache", RiskLevel.DESTRUCTIVE, null, "Clear cache"));
    }

    @Test
    void aHandWrittenCallbackMustDeclareThatItsResultHoldsNoResources() {
        ToolCallback undeclared = new Undeclared();

        assertThatThrownBy(() -> registry.register(undeclared)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resultWithoutResources()");
    }

    private record Undeclared() implements ToolCallback, RiskClassifiedTool {

        @Override
        public RiskLevel riskLevel() {
            return RiskLevel.READ_ONLY;
        }

        @Override
        public String title() {
            return "Undeclared";
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name("search_things").description("d")
                    .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}").build();
        }

        @Override
        public String call(String toolInput) {
            return "";
        }
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
        public boolean resultWithoutResources() {
            return true;
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
