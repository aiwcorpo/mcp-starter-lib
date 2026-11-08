package pl.aiwcorpo.mcp.platform.paging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import pl.aiwcorpo.mcp.platform.autoconfigure.McpPlatformProperties;

/** The key of the paging cursors: given, generated once into the state directory, or (dev, unwritable directory) of the process. */
class PagingCursorKeyTest {

    private static McpPlatformProperties properties(Path stateDir, String key) {
        McpPlatformProperties props = new McpPlatformProperties();
        props.setServerId("mcp-jira");
        props.setStateDir(stateDir.toString());
        props.getPaging().setCursorKey(key);
        return props;
    }

    private static String cursorIssuedBy(CursorCodec codec) {
        return codec.issueFor("user-1", "search", "fp", "position");
    }

    private static boolean opens(CursorCodec codec, String cursor) {
        try {
            codec.openFor("user-1", cursor, "search", "fp");
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private final PlatformPagingAutoConfiguration configuration = new PlatformPagingAutoConfiguration();

    @Test
    void theKeyIsGeneratedOnceAndTheNextStartOpensTheCursorsOfThePreviousOne(@TempDir Path state) throws Exception {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        CursorCodec first = configuration.cursorCodec(properties(state, null), production);
        String cursor = cursorIssuedBy(first);
        CursorCodec second = configuration.cursorCodec(properties(state, null), production);

        assertThat(state.resolve("paging-cursor.key")).isRegularFile();
        assertThat(Base64.getDecoder().decode(Files.readString(state.resolve("paging-cursor.key")).strip())).hasSize(32);
        assertThat(opens(second, cursor)).as("a restart keeps the cursors valid").isTrue();
    }

    @Test
    void anExplicitKeyBeatsTheStateDirectoryAndTwoInstancesWithItShareCursors(@TempDir Path state) {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        CursorCodec one = configuration.cursorCodec(properties(state.resolve("one"), key), production);
        CursorCodec other = configuration.cursorCodec(properties(state.resolve("other"), key), production);

        assertThat(opens(other, cursorIssuedBy(one))).isTrue();
        assertThat(state.resolve("one")).as("nothing is written when the key is given").doesNotExist();
    }

    @Test
    void withoutAnExplicitKeyTwoInstancesWithAStateDirectoryEachDoNotShareCursors(@TempDir Path state) {
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        CursorCodec one = configuration.cursorCodec(properties(state.resolve("one"), null), production);
        CursorCodec other = configuration.cursorCodec(properties(state.resolve("other"), null), production);

        assertThat(opens(other, cursorIssuedBy(one))).isFalse();
    }

    @Test
    void aStateDirectoryThatCannotBeWrittenFallsBackToAKeyOfTheProcess(@TempDir Path directory) throws Exception {
        Path file = Files.writeString(directory.resolve("a-file"), "x");
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("prod");

        CursorCodec codec = configuration.cursorCodec(properties(file, null), production);

        assertThat(opens(codec, cursorIssuedBy(codec))).isTrue();
    }

    @Test
    void aStoredKeyThatIsTooShortIsReplaced(@TempDir Path state) throws Exception {
        Files.writeString(state.resolve("paging-cursor.key"), Base64.getEncoder().encodeToString(new byte[8]));

        byte[] key = PlatformPagingAutoConfiguration.fromStateDirectory(state.resolve("paging-cursor.key"));

        assertThat(key).hasSize(32);
        assertThat(Base64.getDecoder().decode(Files.readString(state.resolve("paging-cursor.key")).strip())).isEqualTo(key);
    }

    @Test
    void theDevProfileKeepsTheKeyInMemoryAndWritesNothing(@TempDir Path state) {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");

        configuration.cursorCodec(properties(state.resolve("dev"), null), dev);

        assertThat(state.resolve("dev")).doesNotExist();
    }
}
