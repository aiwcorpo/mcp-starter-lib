package pl.aiwcorpo.mcp.platform.test.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;
import pl.aiwcorpo.mcp.platform.error.SanitizedToolException;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.observability.AuditLogger;
import pl.aiwcorpo.mcp.platform.policy.PolicyData;
import pl.aiwcorpo.mcp.platform.policy.PolicyEngine;
import pl.aiwcorpo.mcp.platform.resource.ResourceContext;
import pl.aiwcorpo.mcp.platform.resource.ResourcePolicy;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolver;
import pl.aiwcorpo.mcp.platform.resource.ResourceResolvers;

/**
 * {@code context.credentialType} (CC-136): every policy request carries the type from the server's configuration, so a rule that depends on
 * it applies, and a server without the type has no policy at all. The rule here is the one of the Notes fixture of the contracts: a
 * folder outside the caller's scopes is forbidden unless the credential is the user's own.
 */
class CredentialTypeTest {

    private static final PolicyEngine ENGINE = PolicyEngine.fixed(Fixtures.notes(), PolicyData.of(1,
            java.util.Optional.of(Fixtures.notes().policiesHash()), Map.of("mcp-notes-folder-team", List.of("Notes::Folder/team")), List.of("claude-code"), List.of()));

    private static final ResourceResolver<ResolvedResource> FOLDERS = new ResourceResolver<>() {
        @Override
        public String resourceType() {
            return "Notes::Folder";
        }

        @Override
        public Optional<ResolvedResource> fetch(String id) {
            return Optional.of(ResolvedResource.of("Notes::Folder", id, Map.of("path", id)).withScopes(
                    pl.aiwcorpo.mcp.cedar.entities.Scopes.pathPrefixes(id).stream().map(p -> "Notes::Folder/" + p).toList()));
        }

        @Override
        public ResolvedResource describe(ResolvedResource fetched) {
            return fetched;
        }
    };

    private static ResourcePolicy policyWith(List<String> credentialTypes) {
        ToolRiskRegistry registry = new ToolRiskRegistry(ToolRiskRegistry.Unclassified.FAIL);
        registry.register(new RefTool("get_folder", "folderPath", "Notes::Folder"));
        return new ResourcePolicy(ENGINE, new ResourceResolvers(List.of(FOLDERS)), registry,
                new AuditLogger("test.credential.type", "mcp-notes"), "mcp-notes", "notes", credentialTypes, 200);
    }

    private static ResourceContext open(String folder) {
        CallerContext claims = new CallerContext("6f1c2a9e-4d4b-4c51-9a53-1e0f0c7d2b11", "jkowalski",
                List.of("mcp-notes-read", "mcp-notes-folder-team"), "claude-code", "get_folder",
                "0192d1b2-7c3a-7d4e-8f10-2a3b4c5d6e7f", 0L, null);
        return ResourceContext.open(claims, "get_folder", Map.of("folderPath", folder));
    }

    private static String code(Runnable call) {
        return assertThatThrownBy(call::run).isInstanceOf(SanitizedToolException.class).actual() instanceof SanitizedToolException e
                ? e.getCode() : null;
    }

    @Test
    void aRuleOnTheCredentialTypeAppliesToTheTypeOfTheServersConfiguration() {
        try (ResourceContext context = open("elsewhere")) {
            assertThat(code(policyWith(List.of("service-account"))::authorizeCall)).isEqualTo("not_found_or_denied");
            policyWith(List.of("user-oauth")).authorizeCall();
            policyWith(List.of("user-secret")).authorizeCall();
        }
        try (ResourceContext context = open("team/a")) {
            policyWith(List.of("service-account")).authorizeCall();
        }
    }

    @Test
    void aServerWithoutTheTypeHasNoPolicy() {
        assertThatThrownBy(() -> policyWith(List.of())).isInstanceOf(IllegalStateException.class).hasMessageContaining("credential-type");
    }

    @Test
    void aTypeOfNoneOfTheFourIsAnInternalErrorNotAnUnconditionedCheck() {
        try (ResourceContext context = open("team/a")) {
            assertThat(code(policyWith(List.of("password"))::authorizeCall)).isEqualTo("internal_error");
        }
    }
}
