package pl.aiwcorpo.mcp.platform.guard;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.ResolvableType;

/**
 * Last check at startup, next to {@link RegisteredToolsCheck}: the server serves only tools. Resources, resource templates, prompts and
 * completions have no wrapper in this version (no Cedar decision, no audit, no credential contract, no size limit), and CC-259 forbids a
 * server to declare {@code completions} or {@code logging}; the gateway handles resources and prompts itself and sends a server
 * nothing for them. Spring AI 2.0.0 would pick up any {@code Sync*Specification} bean, or a list of them, and declare the matching
 * capability, so a server that has one, or that declares a capability or lists an entry another way ({@code addResource} at startup,
 * {@code spring.ai.mcp.server.capabilities.resource=true}, a server customizer), stops here.
 */
public class UnservedPrimitivesCheck implements SmartInitializingSingleton {

    private static final List<Class<?>> SPECIFICATIONS = List.of(
            McpServerFeatures.SyncResourceSpecification.class, McpServerFeatures.SyncResourceTemplateSpecification.class,
            McpServerFeatures.SyncPromptSpecification.class, McpServerFeatures.SyncCompletionSpecification.class,
            McpServerFeatures.AsyncResourceSpecification.class, McpServerFeatures.AsyncResourceTemplateSpecification.class,
            McpServerFeatures.AsyncPromptSpecification.class, McpServerFeatures.AsyncCompletionSpecification.class);

    private static final List<String> EXPECTED_CAPABILITY_COMPONENTS =
            List.of("completions", "experimental", "logging", "prompts", "resources", "tools");

    private final ObjectProvider<McpSyncServer> servers;
    private final ListableBeanFactory beans;

    public UnservedPrimitivesCheck(ObjectProvider<McpSyncServer> servers, ListableBeanFactory beans) {
        this.servers = servers;
        this.beans = beans;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> found = new ArrayList<>();
        for (Class<?> specification : SPECIFICATIONS) {
            for (ResolvableType type : List.of(ResolvableType.forClass(specification),
                    ResolvableType.forClassWithGenerics(List.class, specification))) {
                for (String name : beans.getBeanNamesForType(type, true, false)) {
                    found.add("bean " + name + " (" + type + ")");
                }
            }
        }
        servers.orderedStream().forEach(server -> {
            withoutLogging(server);
            if (!server.listResources().isEmpty() || !server.listResourceTemplates().isEmpty()) {
                found.add("resources listed by the MCP server");
            }
            if (!server.listPrompts().isEmpty()) {
                found.add("prompts listed by the MCP server");
            }
            McpSchema.ServerCapabilities capabilities = server.getServerCapabilities();
            if (capabilities != null) {
                declared("resources", capabilities.resources(), found);
                declared("prompts", capabilities.prompts(), found);
                declared("completions", capabilities.completions(), found);
                declared("logging", capabilities.logging(), found);
            }
        });
        if (!found.isEmpty()) {
            throw new IllegalStateException("The MCP server is not tools only: " + found + ". Resources, prompts, completions "
                    + "and logging do not go through the platform's wrapper (Cedar, audit, credential, size limit) and the "
                    + "contracts forbid a server to declare completions or logging (CC-259). Remove the "
                    + "Sync*Specification beans and the capability settings; the platform serves tools only until it "
                    + "registers resources and prompts itself.");
        }
    }

    /**
     * MCP SDK 2.0.0 adds the {@code logging} capability to every server in the constructor of {@code McpAsyncServer}, whatever the
     * specification says, and no setting turns it off; CC-259 forbids a server to declare it. The field is replaced by the same
     * capabilities without logging. The handler of {@code logging/setLevel} stays registered but is unreachable: the filter of the
     * internal JWT refuses the method. If the field cannot be replaced (another SDK) the server does not start, because it would
     * declare something the contract forbids.
     */
    private static void withoutLogging(McpSyncServer server) {
        McpSchema.ServerCapabilities declared = server.getServerCapabilities();
        if (declared == null || declared.logging() == null) {
            return;
        }
        McpAsyncServer async = server.getAsyncServer();
        // The copy below lists every component by hand: a version of the record with another component would lose it silently.
        List<String> components = Arrays.stream(McpSchema.ServerCapabilities.class.getRecordComponents())
                .map(RecordComponent::getName).sorted().toList();
        if (!components.equals(EXPECTED_CAPABILITY_COMPONENTS)) {
            throw new IllegalStateException("McpSchema.ServerCapabilities has the components " + components + ", not "
                    + EXPECTED_CAPABILITY_COMPONENTS + ": this version of the MCP SDK is not supported by this version of the starter");
        }
        try {
            Field field = McpAsyncServer.class.getDeclaredField("serverCapabilities");
            field.setAccessible(true);
            field.set(async, new McpSchema.ServerCapabilities(declared.completions(), declared.experimental(), null,
                    declared.prompts(), declared.resources(), declared.tools()));
        } catch (ReflectiveOperationException | RuntimeException ex) {
            throw new IllegalStateException("The MCP SDK declares the logging capability and the starter could not remove it ("
                    + ex.getClass().getSimpleName() + "): this version of the SDK is not supported by this version of the starter", ex);
        }
    }

    private static void declared(String capability, Object value, List<String> found) {
        if (value != null) {
            found.add("capability " + capability + " declared");
        }
    }
}
