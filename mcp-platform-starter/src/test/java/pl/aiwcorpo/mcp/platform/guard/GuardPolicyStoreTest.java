package pl.aiwcorpo.mcp.platform.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;

class GuardPolicyStoreTest {

    @TempDir
    Path dir;

    /** The store's clock, moved by the test; file timestamps advance on their own counter. */
    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicLong mtime = new AtomicLong(1_700_000_000_000L);

    private Path write(String yaml) throws IOException {
        Path file = dir.resolve("policy.yml");
        Files.writeString(file, yaml);
        // Distinct timestamps regardless of filesystem resolution or how fast the test runs.
        Files.setLastModifiedTime(file, FileTime.fromMillis(mtime.addAndGet(5_000)));
        return file;
    }

    private GuardPolicyStore storeFor(Path file) {
        return new GuardPolicyStore(new FileSystemResource(file), 1_000, now::get);
    }

    @Test
    void withoutAFileTheBuiltInDefaultApplies() {
        assertThat(new GuardPolicyStore(null, 1_000).current()).isSameAs(GuardPolicy.DEFAULT);
    }

    @Test
    void picksUpAnEditWithoutARestart() throws IOException {
        Path file = write("tools:\n  repo_purge: { enabled: true }\n");
        GuardPolicyStore store = storeFor(file);
        assertThat(store.current().rule("repo_purge").orElseThrow().enabled()).isTrue();

        write("tools:\n  repo_purge: { enabled: false }\n");
        assertThat(store.current().rule("repo_purge").orElseThrow().enabled())
                .as("not re-checked before the interval elapses").isTrue();

        now.addAndGet(1_000);
        assertThat(store.current().rule("repo_purge").orElseThrow().enabled())
                .as("the kill switch is live on the next call").isFalse();
    }

    @Test
    void aBrokenEditKeepsTheLastGoodPolicyInForce() throws IOException {
        Path file = write("anonymous: deny\n");
        GuardPolicyStore store = storeFor(file);

        write("anonymous: [this is not valid\n");
        now.addAndGet(1_000);
        assertThat(store.current().anonymousMaxRisk())
                .as("a typo must neither open the server up nor take it down").isNull();

        write("anonymous: update\n");
        now.addAndGet(1_000);
        assertThat(store.current().anonymousMaxRisk()).isEqualTo(RiskLevel.UPDATE);
    }

    @Test
    void aFileEmptiedByAHalfFinishedSaveDoesNotDropTheKillSwitches() throws IOException {
        Path file = write("anonymous: deny\ntools:\n  repo_purge: { enabled: false }\n");
        GuardPolicyStore store = storeFor(file);

        write("");
        now.addAndGet(1_000);

        assertThat(store.current().anonymousMaxRisk()).isNull();
        assertThat(store.current().rule("repo_purge").orElseThrow().enabled()).isFalse();
    }

    @Test
    void aBrokenFileAtStartupStopsTheServer() throws IOException {
        Path file = write("tools:\n  repo_purge: { enabeld: false }\n");

        assertThatThrownBy(() -> storeFor(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid guard policy")
                .hasMessageContaining("enabeld");
    }

    @Test
    void aMissingFileAtStartupStopsTheServer() {
        assertThatThrownBy(() -> storeFor(dir.resolve("nope.yml")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot read guard policy");
    }

    @Test
    void aPolicyThatIsNotAFileIsLoadedOnce() {
        GuardPolicyStore store = new GuardPolicyStore(
                new ByteArrayResource("anonymous: update\n".getBytes()), 0, now::get);

        assertThat(store.current().anonymousMaxRisk()).isEqualTo(RiskLevel.UPDATE);
    }
}
