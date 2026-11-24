package pl.aiwcorpo.mcp.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * {@link UnservedPrimitivesCheck} removes the {@code logging} capability from {@code McpAsyncServer} by replacing a private field. That is
 * known to work with MCP SDK 2.0.0 only; this test fails on any other version so that the patch is looked at again (field name, the
 * constructor that adds logging, the components of {@code ServerCapabilities}) before the pin is moved.
 */
class McpSdkVersionPinTest {

    @Test
    void theSdkIsTheVersionTheLoggingPatchWasWrittenFor() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = io.modelcontextprotocol.server.McpAsyncServer.class
                .getResourceAsStream("/META-INF/maven/io.modelcontextprotocol.sdk/mcp-core/pom.properties")) {
            assertThat(in).as("pom.properties of mcp-core").isNotNull();
            properties.load(in);
        }

        assertThat(properties.getProperty("version"))
                .as("MCP SDK changed: re-check UnservedPrimitivesCheck.withoutLogging (private field serverCapabilities of McpAsyncServer, "
                        + "the logging capability added in its constructor) and then move this pin")
                .isEqualTo("2.0.0");
    }
}
