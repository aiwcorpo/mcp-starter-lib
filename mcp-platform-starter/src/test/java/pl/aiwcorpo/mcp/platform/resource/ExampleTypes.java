package pl.aiwcorpo.mcp.platform.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import pl.aiwcorpo.mcp.cedar.entities.ResourceTypes;

/** The resource type declarations of the example systems of mcp-contracts ({@code cedar/examples/systems}), as one registry. */
final class ExampleTypes {

    static final ResourceTypes ALL = load("jira", "confluence", "gitlab", "opensearch", "servicedesk");

    private ExampleTypes() {
    }

    private static ResourceTypes load(String... systems) {
        List<ResourceTypes> registries = new ArrayList<>();
        for (String system : systems) {
            String path = "/mcp-contracts/cedar/examples/systems/" + system + "/resource-types.json";
            try (InputStream in = ExampleTypes.class.getResourceAsStream(path)) {
                registries.add(ResourceTypes.fromJson(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            } catch (IOException ex) {
                throw new java.io.UncheckedIOException(ex);
            }
        }
        return ResourceTypes.union(registries);
    }
}
