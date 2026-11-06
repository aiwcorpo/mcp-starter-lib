package pl.aiwcorpo.mcp.platform.test.resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import pl.aiwcorpo.mcp.platform.policy.ServerPolicies;

/** Policies of fixture servers other than the Jira fragment every test context of this module starts with. */
final class Fixtures {

    private static final String CONTRACTS = "mcp-contracts/test-vectors/fixtures/notes/";

    private Fixtures() {
    }

    /** The neutral Notes fragment of the contracts: a flat type, a child with a parent and a path-shaped type. */
    static ServerPolicies notes() {
        return policies(CONTRACTS, "read.cedar", "update.cedar");
    }

    /** A service desk: queue and ticket (the declarations of the contracts' example) with a read rule; the restricted and service account rules are the generated platform policies. */
    static ServerPolicies serviceDesk() {
        return policies("fixtures/servicedesk/", "read.cedar");
    }

    private static ServerPolicies policies(String directory, String... files) {
        Map<String, String> cedar = new TreeMap<>();
        for (String file : files) {
            cedar.put(file, read(directory + file));
        }
        return ServerPolicies.of(read(directory + "schema.cedarschema"), cedar, read(directory + "resource-types.json"));
    }

    private static String read(String path) {
        try (InputStream in = Fixtures.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }
}
