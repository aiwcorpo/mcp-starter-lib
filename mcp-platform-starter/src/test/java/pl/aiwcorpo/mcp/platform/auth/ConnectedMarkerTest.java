package pl.aiwcorpo.mcp.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** The audience mcp-pending must stay refused after a restart: the fact that the gateway connected lives in the state directory. */
class ConnectedMarkerTest {

    private static JsonNode pendingVector() throws IOException {
        return InternalJwtVectorsTest.vectors().stream()
                .filter(v -> v.name().equals("valid-service-manifest-pending-audience.json")).findFirst().orElseThrow().data();
    }

    private static void verifyPending(ConnectedMarker marker) throws Exception {
        JsonNode data = pendingVector();
        InternalJwtVerifier verifier = InternalJwtVectorsTest.verifierFor(data.get("verifier"), marker::connected);
        verifier.verify(data.get("token").asString(), InternalJwtVectorsTest.bindingFor(data.get("request")));
    }

    @Test
    void beforeAnyPushPendingIsAcceptedAndAfterTheFirstPushItIsNot(@TempDir Path state) throws Exception {
        ConnectedMarker marker = new ConnectedMarker(state);
        verifyPending(marker);
        assertThat(state.resolve("connected")).doesNotExist();

        marker.record();

        assertThat(state.resolve("connected")).exists();
        assertThatThrownBy(() -> verifyPending(marker)).isInstanceOf(InternalJwtRejectedException.class)
                .extracting(ex -> ((InternalJwtRejectedException) ex).reason().code()).isEqualTo("wrong_audience");
    }

    @Test
    void aRestartWithTheMarkerPresentRefusesPendingFromTheBeginning(@TempDir Path state) throws Exception {
        Files.writeString(state.resolve("connected"), "connected\n");

        ConnectedMarker afterRestart = new ConnectedMarker(state);

        assertThat(afterRestart.connected()).isTrue();
        assertThatThrownBy(() -> verifyPending(afterRestart)).isInstanceOf(InternalJwtRejectedException.class)
                .extracting(ex -> ((InternalJwtRejectedException) ex).reason().code()).isEqualTo("wrong_audience");
    }

    @Test
    void aStateDirectoryThatCannotBeWrittenKeepsTheFactInMemory(@TempDir Path directory) throws Exception {
        Path notADirectory = Files.writeString(directory.resolve("file"), "x");
        ConnectedMarker marker = new ConnectedMarker(notADirectory);

        marker.record();

        assertThat(marker.connected()).isTrue();
        assertThat(new ConnectedMarker(notADirectory).connected()).as("nothing was persisted").isFalse();
    }

    @Test
    void withoutAMarkerOrAStateDirectoryNothingIsConnectedYet() {
        assertThat(new ConnectedMarker(null).connected()).isFalse();
        assertThat(List.of(new ConnectedMarker(Path.of("target", "no-such-state-dir"))).get(0).connected()).isFalse();
    }
}
