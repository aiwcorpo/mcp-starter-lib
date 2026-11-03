package pl.aiwcorpo.mcp.platform.capability;

import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link CapabilityProbe} found out about the backend.
 *
 * <ul>
 *   <li>{@code SUPPORTED}: a version the server was written and tested for; {@code features} is the full set.</li>
 *   <li>{@code DEGRADED}: the system answered but its version is unknown to the server (too old, or newer than
 *       anything tested). The server keeps running and serves the conservative baseline, so {@code features} is only
 *       what is known to work on every version; a tool that needs more must refuse with {@code upstream_unavailable}
 *       or fall back, not guess.</li>
 *   <li>{@code UNAVAILABLE}: the system did not answer, or answered something that is not a version. No feature is
 *       assumed.</li>
 * </ul>
 *
 * <p>The version string and the detail come from the backend or from an exception, so both are cut and stripped of
 * everything but a safe set of characters before they reach a log or a metric.
 *
 * @param backendVersion version as the system reports it, safe for logs; {@code null} when unknown
 * @param features       feature codes (snake case, chosen by the server), the vocabulary of {@link #supports(String)}
 * @param detail         why the state is not {@code SUPPORTED}, a short text written by the server or derived from the failure
 */
public record Capabilities(State state, @Nullable String backendVersion, Set<String> features, String detail) {

    public enum State { SUPPORTED, DEGRADED, UNAVAILABLE }

    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._+ -]");
    private static final int MAX_TEXT = 120;
    private static final int MAX_FEATURES = 64;

    public Capabilities {
        features = Set.copyOf(features);
        if (features.size() > MAX_FEATURES) {
            throw new IllegalArgumentException("A probe reports at most " + MAX_FEATURES + " features");
        }
        backendVersion = backendVersion == null ? null : safe(backendVersion);
        detail = safe(detail);
        if (state == State.UNAVAILABLE && !features.isEmpty()) {
            throw new IllegalArgumentException("An unavailable backend has no features");
        }
    }

    public static Capabilities supported(String backendVersion, Set<String> features) {
        return new Capabilities(State.SUPPORTED, backendVersion, features, "");
    }

    /** @param baseline the features that work on every version of the system, nothing the unknown version might lack */
    public static Capabilities degraded(@Nullable String backendVersion, Set<String> baseline, String detail) {
        return new Capabilities(State.DEGRADED, backendVersion, baseline, detail);
    }

    public static Capabilities unavailable(String detail) {
        return new Capabilities(State.UNAVAILABLE, null, Set.of(), detail);
    }

    public boolean supports(String feature) {
        return features.contains(feature);
    }

    public boolean isDegraded() {
        return state != State.SUPPORTED;
    }

    private static String safe(String text) {
        String cleaned = UNSAFE.matcher(text).replaceAll("?");
        return cleaned.length() > MAX_TEXT ? cleaned.substring(0, MAX_TEXT) : cleaned;
    }
}
