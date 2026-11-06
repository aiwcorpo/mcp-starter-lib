package pl.aiwcorpo.mcp.platform.manifest;

import io.modelcontextprotocol.server.McpSyncServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;
import pl.aiwcorpo.mcp.platform.guard.ToolRiskRegistry;
import pl.aiwcorpo.mcp.platform.policy.ServerPolicies;

/**
 * Builds the manifest once the MCP server is up, checks it (a failure stops the server) and keeps it for
 * {@code GET /mcp-manifest}. With {@code aiwcorpo.mcp.platform.manifest.output-file} it is also written to that file,
 * for the step that registers the server at the gateway.
 */
public final class ManifestCheck implements SmartInitializingSingleton {

    private static final Logger LOG = LoggerFactory.getLogger(ManifestCheck.class);

    private final ObjectProvider<McpSyncServer> servers;
    private final McpPlatformProperties props;
    private final ToolRiskRegistry registry;
    private final ObjectProvider<ServerPolicies> policies;
    private final boolean strict;
    private final boolean devEnvironment;
    private volatile String manifest;

    /**
     * @param strict false only in the {@code dev} profile, where a tool with an unbounded schema is allowed and the
     *               manifest is served with a warning instead of stopping the server
     */
    public ManifestCheck(ObjectProvider<McpSyncServer> servers, McpPlatformProperties props, ToolRiskRegistry registry,
                         ObjectProvider<ServerPolicies> policies, boolean strict) {
        this(servers, props, registry, policies, strict, false);
    }

    /**
     * @param devEnvironment true with the {@code devstack} profile: the manifest then carries {@code environment: dev} (CC-138)
     */
    public ManifestCheck(ObjectProvider<McpSyncServer> servers, McpPlatformProperties props, ToolRiskRegistry registry,
                         ObjectProvider<ServerPolicies> policies, boolean strict, boolean devEnvironment) {
        this.devEnvironment = devEnvironment;
        this.strict = strict;
        this.servers = servers;
        this.props = props;
        this.registry = registry;
        this.policies = policies;
    }

    @Override
    public void afterSingletonsInstantiated() {
        McpSyncServer server = servers.getIfAvailable();
        if (server == null) {
            return;
        }
        manifest = ManifestGenerator.generate(props, registry, server.listTools(), policies.getIfAvailable(), strict, devEnvironment);
        String file = props.getManifest().getOutputFile();
        if (file != null && !file.isBlank()) {
            try {
                Path path = Path.of(file);
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.writeString(path, manifest + "\n", StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw new UncheckedIOException("cannot write the manifest to " + file, ex);
            }
        }
        LOG.info("event=manifest_ready tools={}", registry.toolNames().size());
    }

    /** The manifest, empty when the server has no MCP server (a slice test). */
    public Optional<String> manifest() {
        return Optional.ofNullable(manifest);
    }
}
