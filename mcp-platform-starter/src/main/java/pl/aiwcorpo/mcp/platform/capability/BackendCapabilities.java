package pl.aiwcorpo.mcp.platform.capability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The latest answer of every {@link CapabilityProbe}, for tools to consult while they run. Before the first answer a
 * backend is {@code UNAVAILABLE} ("not probed yet"), so a tool never assumes a feature that nobody confirmed.
 */
public final class BackendCapabilities {

    private static final Capabilities NOT_PROBED = Capabilities.unavailable("not probed yet");

    private final Map<String, Capabilities> latest = new ConcurrentHashMap<>();

    void update(String name, Capabilities capabilities) {
        latest.put(name, capabilities);
    }

    public Capabilities of(String name) {
        return latest.getOrDefault(name, NOT_PROBED);
    }

    /** True when {@code name} is anything but {@code SUPPORTED}: the server is in its conservative mode. */
    public boolean isDegraded(String name) {
        return of(name).isDegraded();
    }

    Map<String, Capabilities> all() {
        return Map.copyOf(latest);
    }
}
