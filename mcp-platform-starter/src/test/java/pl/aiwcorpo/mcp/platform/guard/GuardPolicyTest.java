package pl.aiwcorpo.mcp.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class GuardPolicyTest {

    private static GuardPolicy parse(String yaml) {
        return GuardPolicy.fromYaml(new Yaml().load(yaml));
    }

    @Test
    void anEmptyFileIsAnErrorNotTheDefaultPolicy() {
        assertThatThrownBy(() -> parse("")).hasMessageContaining("the policy file is empty");
        assertThatThrownBy(() -> parse("# everything commented out\n")).hasMessageContaining("empty");
    }

    @Test
    void whatThePolicyDoesNotSayIsLeastPrivilege() {
        GuardPolicy policy = parse("tools: {}");

        assertThat(policy.anonymousMaxRisk()).isEqualTo(RiskLevel.READ_ONLY);
        assertThat(policy.authenticatedMaxRisk()).isEqualTo(RiskLevel.READ_ONLY);
        assertThat(policy.patMaxRisk()).as("holding a PAT is not a privilege either").isEqualTo(RiskLevel.READ_ONLY);
        assertThat(parse("pat: update").patMaxRisk()).isEqualTo(RiskLevel.UPDATE);
        assertThat(policy.confirmation().requiredFrom()).isEqualTo(RiskLevel.DESTRUCTIVE);
        assertThat(policy.roles()).isEmpty();
    }

    @Test
    void readsRolesToolRulesAndConfirmation() {
        GuardPolicy policy = parse("""
                anonymous: deny
                authenticated: read-only
                roles:
                  mcp-operator: { max-risk: update }
                  mcp-admin:
                    max-risk: destructive
                    deny-tools: [repo_purge]
                tools:
                  repo_purge: { enabled: false }
                  repo_rename: { risk: destructive, confirmation: true }
                confirmation:
                  required-from: update
                  ttl-seconds: 30
                  max-attempts: 2
                """);

        assertThat(policy.anonymousMaxRisk()).isNull();
        assertThat(policy.roles().get("mcp-operator").maxRisk()).isEqualTo(RiskLevel.UPDATE);
        assertThat(policy.roles().get("mcp-admin").denyTools()).containsExactly("repo_purge");
        assertThat(policy.rule("repo_purge")).get().satisfies(rule -> assertThat(rule.enabled()).isFalse());
        assertThat(policy.rule("repo_rename")).get().satisfies(rule -> {
            assertThat(rule.enabled()).isTrue();
            assertThat(rule.risk()).isEqualTo(RiskLevel.DESTRUCTIVE);
            assertThat(rule.confirmation()).isTrue();
        });
        assertThat(policy.confirmation().requiredFrom()).isEqualTo(RiskLevel.UPDATE);
        assertThat(policy.confirmation().ttl()).hasSeconds(30);
        assertThat(policy.confirmation().maxAttempts()).isEqualTo(2);
    }

    @Test
    void aValidTokenAloneGrantsOnlyTheAuthenticatedLevel() {
        GuardPolicy policy = parse("roles:\n  mcp-admin: { max-risk: destructive }");

        CallerIdentity nobody = policy.identityFor("anna", List.of("some-other-app-role"));

        assertThat(nobody.authenticated()).isTrue();
        assertThat(nobody.maxRisk()).as("being logged in is not a privilege").isEqualTo(RiskLevel.READ_ONLY);
    }

    @Test
    void aCallerGetsTheHighestLevelAmongTheirRolesAndEveryDeny() {
        GuardPolicy policy = parse("""
                roles:
                  mcp-operator: { max-risk: update, deny-tools: [repo_archive] }
                  mcp-admin: { max-risk: destructive, deny-tools: [repo_purge] }
                """);

        CallerIdentity karol = policy.identityFor("karol", List.of("mcp-operator", "mcp-admin", "unrelated"));

        assertThat(karol.maxRisk()).isEqualTo(RiskLevel.DESTRUCTIVE);
        assertThat(karol.denyTools()).containsExactlyInAnyOrder("repo_archive", "repo_purge");
        assertThat(karol.allowTools()).isEmpty();
    }

    @Test
    void anAllowListRestrictsOnlyWhenEveryRoleOfTheCallerHasOne() {
        GuardPolicy policy = parse("""
                roles:
                  report-bot: { max-risk: read-only, allow-tools: [repo_get] }
                  audit-bot: { max-risk: read-only, allow-tools: [repo_log] }
                  mcp-operator: { max-risk: update }
                """);

        assertThat(policy.identityFor("bot", List.of("report-bot", "audit-bot")).allowTools())
                .containsExactlyInAnyOrder("repo_get", "repo_log");
        assertThat(policy.identityFor("ola", List.of("report-bot", "mcp-operator")).allowTools())
                .as("an unrestricted role is not narrowed by also holding a restricted one").isEmpty();
    }

    @Test
    void usersDoNotBelongInThePolicyFile() {
        assertThatThrownBy(() -> parse("identities:\n  - subject: a\n    token: x"))
                .hasMessageContaining("unknown key 'identities'");
        assertThatThrownBy(() -> parse("roles:\n  mcp-admin: { }")).hasMessageContaining("roles.mcp-admin.max-risk is required");
    }

    @Test
    void aMisspeltKeyIsAnErrorNotASilentlyIgnoredKillSwitch() {
        assertThatThrownBy(() -> parse("""
                tools:
                  repo_purge: { enabeld: false }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("enabeld")
                .hasMessageContaining("tools.repo_purge");
    }

    @Test
    void enabledWithNothingAfterItIsNotEnabledTrue() {
        assertThatThrownBy(() -> parse("tools:\n  t:\n    enabled:"))
                .hasMessageContaining("tools.t.enabled must be true or false");
    }

    @Test
    void rejectsWrongTypesAndUnknownLevels() {
        assertThatThrownBy(() -> parse("tools:\n  t: { enabled: \"no\" }"))
                .hasMessageContaining("tools.t.enabled must be true or false");
        assertThatThrownBy(() -> parse("anonymous: admin"))
                .hasMessageContaining("unknown risk level 'admin'");
    }
}
