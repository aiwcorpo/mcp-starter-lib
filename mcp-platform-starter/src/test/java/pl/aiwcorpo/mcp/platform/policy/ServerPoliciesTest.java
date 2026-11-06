package pl.aiwcorpo.mcp.platform.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.manifest.PoliciesHash;

/** The policies of the jar: loaded from {@code src/test/resources/mcp/policies} (the Jira fragment of the contracts' examples). */
class ServerPoliciesTest {

    private static final String SCHEMA = """
            namespace Notes {
              entity Note { title: String, scopes: Set<ResourceScope> };
              action "read", "update", "delete", "admin" appliesTo { principal: [User], resource: [Note], context: RequestContext };
            }
            """;
    private static final String TYPES = "[{\"type\":\"Notes::Note\",\"attributes\":[\"title\"],\"idPattern\":\"^[0-9]{1,9}$\"}]";
    private static final String READ = "@id(\"notes-read\")\npermit (principal in UserGroup::\"mcp-notes-read\", "
            + "action == Notes::Action::\"read\", resource);\n";

    @Test
    void theFragmentOfTheJarIsLoadedAndValidated() {
        ServerPolicies policies = ServerPolicies.load(getClass().getClassLoader());

        assertThat(policies.namespace()).isEqualTo("Jira");
        assertThat(policies.files()).containsOnlyKeys("forbid-restricted.cedar", "read.cedar", "update.cedar");
        assertThat(policies.resourceTypes().isDeclared("Jira::Issue")).isTrue();
        assertThat(policies.manifestPolicies().get("files").get(0).get("name").asString()).isEqualTo("forbid-restricted.cedar");
    }

    @Test
    void theHashCoversThePoliciesAndTheDeclarations() {
        ServerPolicies one = ServerPolicies.of(SCHEMA, Map.of("read.cedar", READ), TYPES);
        ServerPolicies same = ServerPolicies.of(SCHEMA, Map.of("read.cedar", READ), TYPES);
        ServerPolicies otherPattern = ServerPolicies.of(SCHEMA, Map.of("read.cedar", READ), TYPES.replace("{1,9}", "{1,12}"));
        ServerPolicies otherPolicy = ServerPolicies.of(SCHEMA, Map.of("read.cedar", READ.replace("notes-read", "notes-view")), TYPES);

        assertThat(one.policiesHash()).startsWith("sha256:").isEqualTo(same.policiesHash())
                .isNotEqualTo(otherPattern.policiesHash()).isNotEqualTo(otherPolicy.policiesHash());
        assertThat(one.policiesHash()).isEqualTo(PoliciesHash.of(one.manifestPolicies().toString(), TYPES));
    }

    @Test
    void anEntityTypeWithoutScopesIsRefusedWithTheReason() {
        String noScopes = SCHEMA.replace(", scopes: Set<ResourceScope>", "");

        assertThatThrownBy(() -> ServerPolicies.of(noScopes, Map.of("read.cedar", READ), TYPES))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("mcp/policies")
                .hasMessageContaining("the gateway would reject them").hasMessageContaining("scopes");
    }

    @Test
    void aTypeWithoutADeclarationAndAPolicyThatMentionsTheGatewayAreRefused() {
        String twoTypes = SCHEMA.replace("entity Note {", "entity Tag { scopes: Set<ResourceScope> };\n  entity Note {");
        String call = "@id(\"x\")\npermit (principal, action == Action::\"call\", resource is Tool);\n";

        assertThatThrownBy(() -> ServerPolicies.of(twoTypes, Map.of("read.cedar", READ), TYPES))
                .hasMessageContaining("Tag has no declaration");
        assertThatThrownBy(() -> ServerPolicies.of(SCHEMA, Map.of("read.cedar", READ, "call.cedar", call), TYPES))
                .hasMessageContaining("belong to the gateway");
    }

    @Test
    void declarationsInTwoNamespacesAndAnEmptyFileSetAreRefused() {
        String two = "[{\"type\":\"Notes::Note\",\"attributes\":[],\"idPattern\":\"^a$\"},{\"type\":\"Other::Thing\",\"attributes\":[],\"idPattern\":\"^a$\"}]";

        assertThatThrownBy(() -> ServerPolicies.of(SCHEMA, Map.of("read.cedar", READ), two)).hasMessageContaining("one Cedar namespace");
        assertThatThrownBy(() -> ServerPolicies.of(SCHEMA, Map.of("Read.cedar", READ), TYPES)).hasMessageContaining("file name");
        assertThatThrownBy(() -> ServerPolicies.of(SCHEMA, Map.of(), "[]")).hasMessageContaining("declares no resource type");
    }

    @Test
    void aClasspathWithoutPoliciesSaysWhereTheyGo() {
        ClassLoader empty = new java.net.URLClassLoader(new java.net.URL[0], null);

        assertThatThrownBy(() -> ServerPolicies.load(empty)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("src/main/resources/mcp/policies/schema.cedarschema is missing");
    }

    @Test
    void policyFilesFromAnotherPlaceOnTheClasspathStopTheStart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path own = java.nio.file.Files.createDirectories(dir.resolve("own/mcp/policies"));
        java.nio.file.Path library = java.nio.file.Files.createDirectories(dir.resolve("library/mcp/policies"));
        java.nio.file.Files.writeString(own.resolve("schema.cedarschema"), SCHEMA);
        java.nio.file.Files.writeString(own.resolve("resource-types.json"), TYPES);
        java.nio.file.Files.writeString(own.resolve("read.cedar"), READ);
        ClassLoader alone = new java.net.URLClassLoader(new java.net.URL[] {dir.resolve("own").toUri().toURL()}, null);
        assertThat(ServerPolicies.load(alone).namespace()).isEqualTo("Notes");

        java.nio.file.Files.writeString(library.resolve("extra.cedar"), READ.replace("notes-read", "notes-extra"));
        ClassLoader both = new java.net.URLClassLoader(new java.net.URL[] {dir.resolve("own").toUri().toURL(),
                dir.resolve("library").toUri().toURL()}, null);

        assertThatThrownBy(() -> ServerPolicies.load(both)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("more than one place");
    }

    @Test
    void policiesOfTheApplicationsOwnCodeAreAccepted() {
        ServerPolicies policies = ServerPolicies.load(getClass().getClassLoader(), java.util.List.of(ServerPoliciesTest.class));

        assertThat(policies.namespace()).isEqualTo("Jira");
        assertThat(ServerPolicies.loadFromDirectory(getClass().getClassLoader()).namespace()).isEqualTo("Jira");
    }

    @Test
    void policiesOnlyInALibraryJarAreRefusedWhateverTheApplicationIs(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path jar = dir.resolve("mcp-tools-1.0.0.jar");
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            for (String[] file : new String[][] {{"schema.cedarschema", SCHEMA}, {"resource-types.json", TYPES}, {"read.cedar", READ}}) {
                out.putNextEntry(new java.util.jar.JarEntry("mcp/policies/" + file[0]));
                out.write(file[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        ClassLoader loader = new java.net.URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, ServerPoliciesTest.class.getClassLoader()) {
            @Override
            public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
                // Only the library holds mcp/policies in this loader; the classes of the test stay visible.
                return name.startsWith("mcp/policies") ? findResources(name) : super.getResources(name);
            }
        };

        assertThatThrownBy(() -> ServerPolicies.load(loader, java.util.List.of(ServerPoliciesTest.class)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not from the application's own code");
        assertThatThrownBy(() -> ServerPolicies.loadFromDirectory(loader)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not the module under test");
        assertThatThrownBy(() -> ServerPolicies.load(loader, java.util.List.of())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no application class");
    }
}
