package pl.aiwcorpo.mcp.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.yaml.snakeyaml.Yaml;

/**
 * The decision table of the guard, without a server: who may call what, and when a human must
 * confirm. The same rules over the real transport are in {@code GuardOverTransportTest}.
 */
class ToolGuardTest {

    private static final String POLICY = """
            roles:
              operator: { max-risk: update }
              admin: { max-risk: destructive }
              report-bot: { max-risk: destructive, allow-tools: [repo_get] }
            tools:
              repo_archive: { enabled: false }
              repo_rename: { risk: destructive }
              repo_purge: { risk: read-only }
              repo_sync: { risk: read-only }
            """;

    private final GuardPolicy policy = GuardPolicy.fromYaml(new Yaml().load(POLICY));

    private String sentCode;
    private Optional<ConfirmationPrompter.Reply> promptReply = Optional.empty();   // empty = client cannot be prompted
    private String promptMessage;

    private final ToolGuard guard = newGuard(ToolRiskRegistry.Unclassified.FAIL);

    private ToolGuard newGuard(ToolRiskRegistry.Unclassified unclassified) {
        return new ToolGuard(
                new GuardPolicyStore(new ByteArrayResource(POLICY.getBytes()), 1_000),
                new ToolRiskRegistry(unclassified),
                new ConfirmationService((identity, tool, summary, code, expiresAt) -> sentCode = code),
                (context, message) -> {
                    promptMessage = message;
                    return promptReply;
                });
    }

    @AfterEach
    void clearCaller() {
        CallerContext.clear();
    }

    /** anna has no role, ola is an operator, karol an admin, bot a narrowly scoped service account. */
    private CallerIdentity identity(String who) {
        List<String> roles = switch (who) {
            case "ola" -> List.of("operator");
            case "karol" -> List.of("admin");
            case "bot" -> List.of("report-bot");
            default -> List.of();
        };
        return policy.identityFor(who, roles);
    }

    private void callingAs(String token) {
        CallerContext.set(identity(token.substring(2)));
    }

    private ToolCallback registered(String name, RiskLevel risk) {
        ToolCallback tool = tool(name, risk);
        guard.register(tool);
        return tool;
    }

    private ToolGuard.Admission admit(ToolCallback tool, String input) {
        return guard.admit(tool, input, new ToolContext(java.util.Map.of()));
    }

    // ------------------------------------------------------------------- do I have the privilege?

    @Test
    void aCallerMayUseToolsUpToTheirRiskLevel() {
        ToolCallback get = registered("repo_get", RiskLevel.READ_ONLY);
        ToolCallback comment = registered("repo_comment", RiskLevel.UPDATE);

        callingAs("t-anna");
        assertThat(admit(get, "{}").identity().subject()).isEqualTo("anna");
        assertThatThrownBy(() -> admit(comment, "{}"))
                .isInstanceOfSatisfying(ToolRejectedException.class, rejected -> {
                    assertThat(rejected.getCode()).isEqualTo("access_denied");
                    assertThat(rejected.getMessage()).contains("repo_comment", "update", "anna", "read-only");
                });

        callingAs("t-ola");
        assertThat(admit(comment, "{}").risk()).isEqualTo(RiskLevel.UPDATE);
    }

    @Test
    void withoutCredentialsACallerIsAnonymousAndReadOnly() {
        ToolCallback get = registered("repo_get", RiskLevel.READ_ONLY);
        ToolCallback comment = registered("repo_comment", RiskLevel.UPDATE);

        assertThat(admit(get, "{}").identity().subject()).isEqualTo("anonymous");
        assertThatThrownBy(() -> admit(comment, "{}")).hasMessageContaining("access_denied");
    }

    @Test
    void theKillSwitchStopsEvenTheMostPrivilegedCaller() {
        ToolCallback archive = registered("repo_archive", RiskLevel.UPDATE);

        callingAs("t-karol");
        assertThatThrownBy(() -> admit(archive, "{}"))
                .isInstanceOfSatisfying(ToolRejectedException.class,
                        rejected -> assertThat(rejected.getCode()).isEqualTo("tool_disabled"));
    }

    @Test
    void anAllowListNarrowsACallerBelowTheirRiskLevel() {
        ToolCallback get = registered("repo_get", RiskLevel.READ_ONLY);
        ToolCallback list = registered("repo_list", RiskLevel.READ_ONLY);

        callingAs("t-bot");
        assertThat(admit(get, "{}")).isNotNull();
        assertThatThrownBy(() -> admit(list, "{}")).hasMessageContaining("not permitted to use tool 'repo_list'");
    }

    @Test
    void thePolicyCanRaiseAToolsRiskButNeverLowerIt() {
        ToolCallback rename = registered("repo_rename", RiskLevel.UPDATE);          // policy: destructive
        ToolCallback purge = registered("repo_purge", RiskLevel.DESTRUCTIVE);       // policy: read-only (ignored)

        assertThat(guard.riskOf(rename)).isEqualTo(RiskLevel.DESTRUCTIVE);
        assertThat(guard.riskOf(purge)).isEqualTo(RiskLevel.DESTRUCTIVE);

        callingAs("t-ola");
        assertThatThrownBy(() -> admit(rename, "{}")).hasMessageContaining("access_denied");
    }

    // ----------------------------------------------------------------------------- startup checks

    @Test
    void aToolWithoutARiskFlagStopsTheServer() {
        assertThatThrownBy(() -> guard.register(tool("repo_sync", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("'repo_sync' has no risk flag");
    }

    @Test
    void thePolicyFileCannotVouchForAnUnflaggedTool() {
        // The policy says "repo_sync: read-only", and the tool has no flag in code.
        assertThatThrownBy(() -> guard.register(tool("repo_sync", null))).hasMessageContaining("no risk flag");

        ToolGuard lenient = newGuard(ToolRiskRegistry.Unclassified.DESTRUCTIVE);
        ToolCallback sync = tool("repo_sync", null);
        lenient.register(sync);
        assertThat(lenient.riskOf(sync)).as("unflagged stays destructive whatever the policy says")
                .isEqualTo(RiskLevel.DESTRUCTIVE);
    }

    /** Real {@code @Tool} methods, on objects that are NOT Spring beans. */
    static class Flagged {
        @Tool(name = "ledger_reset", description = "d")
        @ToolRisk(RiskLevel.DESTRUCTIVE)
        public String reset() {
            return "reset";
        }
    }

    static class Unflagged {
        @Tool(name = "ledger_reset", description = "d")
        public String reset() {
            return "reset";
        }
    }

    @Test
    void theFlagIsReadFromTheToolsOwnMethodNotLookedUpByName() {
        ToolCallback flagged = MethodToolCallbackProvider.builder().toolObjects(new Flagged()).build().getToolCallbacks()[0];
        ToolCallback unflagged = MethodToolCallbackProvider.builder().toolObjects(new Unflagged()).build().getToolCallbacks()[0];

        assertThat(guard.register(flagged)).isEqualTo(RiskLevel.DESTRUCTIVE);
        assertThatThrownBy(() -> guard.register(unflagged))
                .as("a same-named tool elsewhere must not lend its flag")
                .hasMessageContaining("'ledger_reset' has no risk flag");
    }

    @Test
    void aToolMayNotClaimTheReservedConfirmationParameter() {
        ToolCallback clash = new FlaggedTool("repo_tag", RiskLevel.UPDATE) {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name("repo_tag").description("d")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"confirmation_code\":{\"type\":\"integer\"}}}")
                        .build();
            }
        };

        assertThatThrownBy(() -> guard.register(clash)).hasMessageContaining("reserved by the platform");
    }

    @Test
    void anUnflaggedToolCanBeToleratedButOnlyAsDestructive() {
        ToolGuard lenient = newGuard(ToolRiskRegistry.Unclassified.DESTRUCTIVE);
        ToolCallback sync = tool("repo_sync", null);

        assertThat(lenient.register(sync)).isEqualTo(RiskLevel.DESTRUCTIVE);
    }

    @Test
    void aNameThatContradictsItsFlagStopsTheServer() {
        for (String name : List.of("repo_delete", "dropDatabase", "cache-purge", "remove_member",
                "XMLDelete", "HTTPDrop", "delete2", "repo_deletes")) {
            assertThatThrownBy(() -> guard.register(tool(name, RiskLevel.UPDATE)))
                    .as(name)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Flag it @ToolRisk(RiskLevel.DESTRUCTIVE)");
        }
        assertThat(guard.register(tool("repo_delete", RiskLevel.DESTRUCTIVE))).isEqualTo(RiskLevel.DESTRUCTIVE);
        assertThat(guard.register(tool("dropdown_options", RiskLevel.READ_ONLY)))
                .as("'dropdown' is not 'drop'").isEqualTo(RiskLevel.READ_ONLY);
        assertThat(guard.register(tool("list_deleted_items", RiskLevel.READ_ONLY)))
                .as("'deleted' names a state, not an action").isEqualTo(RiskLevel.READ_ONLY);
    }

    // -------------------------------------------------------------------- destructive: two calls

    @Test
    void aDestructiveCallIsRefusedUntilTheHumanSuppliesTheCode() {
        ToolCallback drop = registered("db_drop", RiskLevel.DESTRUCTIVE);
        callingAs("t-karol");

        assertThatThrownBy(() -> admit(drop, "{\"name\":\"orders\"}"))
                .isInstanceOfSatisfying(ToolRejectedException.class, rejected -> {
                    assertThat(rejected.getCode()).isEqualTo("confirmation_required");
                    assertThat(rejected.getMessage())
                            .as("the model is told what to do, and is never given the code")
                            .contains("confirmation_code", "karol")
                            .doesNotContain(sentCode);
                });

        ToolGuard.Admission admission = admit(drop, "{\"confirmation_code\":\"" + sentCode + "\",\"name\":\"orders\"}");
        assertThat(admission.confirmed()).isTrue();
        assertThat(admission.toolInput())
                .as("the tool never sees the confirmation code").isEqualTo("{\"name\":\"orders\"}");
    }

    @Test
    void aCodeApprovedForOneTargetCannotBeSpentOnAnother() {
        ToolCallback drop = registered("db_drop", RiskLevel.DESTRUCTIVE);
        callingAs("t-karol");
        assertThatThrownBy(() -> admit(drop, "{\"name\":\"scratch\"}")).hasMessageContaining("confirmation_required");

        assertThatThrownBy(() -> admit(drop, "{\"name\":\"production\",\"confirmation_code\":\"" + sentCode + "\"}"))
                .isInstanceOfSatisfying(ToolRejectedException.class, rejected -> {
                    assertThat(rejected.getCode()).isEqualTo("confirmation_failed");
                    assertThat(rejected.getMessage()).contains("no pending confirmation for this exact call");
                });
    }

    @Test
    void theApproverSeesTheArgumentsInAFixedOrderAndIsToldWhenTheyAreCutOff() {
        ToolCallback drop = registered("db_drop", RiskLevel.DESTRUCTIVE);
        callingAs("t-karol");
        ToolGuard typing = promptingGuard(true, true);
        typing.register(drop);

        typing.admit(drop, "{\"zzz\":\"" + "x".repeat(50) + "\",\"name\":\"production\"}", null);
        assertThat(promptMessage).as("sorted keys: the model cannot push the target to the end")
                .contains("Arguments: {\"name\":\"production\",\"zzz\"");

        typing.admit(drop, "{\"name\":\"" + "x".repeat(2_000) + "\"}", null);
        assertThat(promptMessage).contains("TRUNCATED", "You are not seeing the whole call");
    }

    @Test
    void theJsonLiteralNullIsACallWithNoArguments() {
        ToolCallback comment = registered("repo_comment", RiskLevel.UPDATE);
        callingAs("t-ola");

        assertThat(admit(comment, "null").risk()).isEqualTo(RiskLevel.UPDATE);
    }

    @Test
    void aWrongCodeDoesNotRunTheTool() {
        ToolCallback drop = registered("db_drop", RiskLevel.DESTRUCTIVE);
        callingAs("t-karol");
        assertThatThrownBy(() -> admit(drop, "{}")).hasMessageContaining("confirmation_required");
        String wrong = sentCode.startsWith("0") ? "111-111" : "000-000";

        assertThatThrownBy(() -> admit(drop, "{\"confirmation_code\":\"" + wrong + "\"}"))
                .hasMessageContaining("confirmation_failed: The confirmation code is wrong.");
    }

    @Test
    void publishesTheConfirmationArgumentOnlyOnToolsThatCanNeedIt() {
        ToolCallback get = registered("repo_get", RiskLevel.READ_ONLY);
        ToolCallback drop = registered("db_drop", RiskLevel.DESTRUCTIVE);

        assertThat(guard.publishedSchema(get)).doesNotContain("confirmation_code");
        assertThat(guard.publishedSchema(drop))
                .contains("\"confirmation_code\"")
                .contains("\"name\"")
                .contains("\"required\":[\"name\"]");
    }

    // ------------------------------------------------------------ destructive: wait for interaction

    @Test
    void aClientThatCanPromptConfirmsInsideTheSameCall() {
        ToolCallback drop = registered("db_drop", RiskLevel.DESTRUCTIVE);
        callingAs("t-karol");
        ToolGuard typing = promptingGuard(true, true);
        typing.register(drop);

        ToolGuard.Admission admission = typing.admit(drop, "{\"name\":\"orders\"}", null);

        assertThat(admission.confirmed()).isTrue();
        assertThat(promptMessage).contains("db_drop", "orders", "karol").doesNotContain(sentCode);
    }

    @Test
    void decliningOrMistypingInThePromptDoesNotRunTheTool() {
        ToolCallback drop = registered("db_drop", RiskLevel.DESTRUCTIVE);
        callingAs("t-karol");

        ToolGuard declining = promptingGuard(false, true);
        declining.register(drop);
        assertThatThrownBy(() -> declining.admit(drop, "{}", null)).hasMessageContaining("confirmation_declined");

        ToolGuard mistyping = promptingGuard(true, false);
        mistyping.register(drop);
        assertThatThrownBy(() -> mistyping.admit(drop, "{}", null)).hasMessageContaining("confirmation_failed");
    }

    /** A guard whose client answers the prompt by typing the code that was just sent (or a wrong one). */
    private ToolGuard promptingGuard(boolean accept, boolean correctCode) {
        return new ToolGuard(
                new GuardPolicyStore(new ByteArrayResource(POLICY.getBytes()), 1_000),
                new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL),
                new ConfirmationService((identity, tool, summary, code, expiresAt) -> sentCode = code),
                (context, message) -> {
                    promptMessage = message;
                    String typed = correctCode ? sentCode : (sentCode.startsWith("0") ? "111-111" : "000-000");
                    return Optional.of(new ConfirmationPrompter.Reply(accept, accept ? typed : null));
                });
    }

    @Test
    void whoAmIListsWhatTheCallerMayDo() {
        registered("repo_get", RiskLevel.READ_ONLY);
        registered("repo_comment", RiskLevel.UPDATE);
        registered("db_drop", RiskLevel.DESTRUCTIVE);

        List<ToolGuard.Permission> ola = guard.permissionsFor(identity("ola"));
        assertThat(ola).extracting(ToolGuard.Permission::tool, ToolGuard.Permission::allowed)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("db_drop", false),
                        org.assertj.core.groups.Tuple.tuple("repo_comment", true),
                        org.assertj.core.groups.Tuple.tuple("repo_get", true));

        List<ToolGuard.Permission> karol = guard.permissionsFor(identity("karol"));
        assertThat(karol).filteredOn(p -> p.tool().equals("db_drop"))
                .singleElement().satisfies(p -> {
                    assertThat(p.allowed()).isTrue();
                    assertThat(p.needsConfirmation()).isTrue();
                });
    }

    private static ToolCallback tool(String name, RiskLevel risk) {
        return risk == null ? new PlainTool(name) : new FlaggedTool(name, risk);
    }

    /** A hand-written tool with no risk flag anywhere. */
    private static class PlainTool implements ToolCallback {

        static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}},"
                + "\"required\":[\"name\"],\"additionalProperties\":false}";

        private final String name;

        PlainTool(String name) {
            this.name = name;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder().name(name).description("test").inputSchema(SCHEMA).build();
        }

        @Override
        public String call(String toolInput) {
            return "ran";
        }
    }

    /** A hand-written tool that declares its risk through {@link RiskClassifiedTool}. */
    private static class FlaggedTool extends PlainTool implements RiskClassifiedTool {

        private final RiskLevel risk;

        FlaggedTool(String name, RiskLevel risk) {
            super(name);
            this.risk = risk;
        }

        @Override
        public RiskLevel riskLevel() {
            return risk;
        }
    }
}
