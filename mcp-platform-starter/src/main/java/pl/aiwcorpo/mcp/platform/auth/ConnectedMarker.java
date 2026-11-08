package pl.aiwcorpo.mcp.platform.auth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers that the gateway has pushed policy data to this server, that is that it was connected. The audience {@code mcp-pending} of the
 * add operation is shared by every server, so it is valid only until then (CC-249). The fact is a marker file {@value #FILE} in the state
 * directory, written after the first accepted push and read at start, so a restart does not open the window again. When the directory
 * cannot be written the server logs a warning and keeps the fact in memory only.
 */
public final class ConnectedMarker {

    static final String FILE = "connected";

    private static final Logger LOG = LoggerFactory.getLogger(ConnectedMarker.class);

    private final Path file;
    private final AtomicBoolean persisted;
    private volatile boolean connected;

    public ConnectedMarker(Path stateDir) {
        this.file = stateDir == null ? null : stateDir.resolve(FILE);
        this.connected = file != null && Files.exists(file);
        this.persisted = new AtomicBoolean(connected);
    }

    /** True once a push was accepted, in this process or in an earlier one that left the marker. */
    public boolean connected() {
        return connected;
    }

    /** Called after the gateway's push was accepted. */
    public void record() {
        connected = true;
        if (file == null || !persisted.compareAndSet(false, true)) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "connected\n");
        } catch (IOException | RuntimeException ex) {
            LOG.warn("event=connected_marker_not_written path={} detail=\"{}; mcp-pending stays refused until this process ends, a restart accepts it again\"",
                    file, ex.getClass().getSimpleName());
        }
    }
}
