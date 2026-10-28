package pl.aiwcorpo.mcp.platform.resource;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.platform.auth.CallerContext;

/**
 * What the platform knows about the tool call in progress, for the thread of the request: who calls, which tool, with
 * which arguments, and the resources already resolved. Opened by the platform around every call (a tool does not open
 * one), so it exists exactly while a tool runs.
 *
 * <p>Resources are cached by type and id for the duration of the call, so the check of an argument and the filtering of
 * a result that names the same resource cost one call to the system.
 */
public final class ResourceContext implements AutoCloseable {

    private static final ThreadLocal<ResourceContext> CURRENT = new ThreadLocal<>();

    /** A resource fetched from the system, or the fact that it is not there. */
    record Resolved(Object fetched, ResolvedResource resource) {
    }

    private final CallerContext caller;
    private final String tool;
    private final Map<String, Object> arguments;
    private final Map<String, Resolved> resolved = new HashMap<>();
    private final ResourceContext previous;

    private ResourceContext(CallerContext caller, String tool, Map<String, Object> arguments) {
        this.caller = caller;
        this.tool = tool;
        this.arguments = arguments;
        this.previous = CURRENT.get();
    }

    /** Opens the context of one call; close it in {@code finally}. */
    public static ResourceContext open(CallerContext caller, String tool, Map<String, Object> arguments) {
        ResourceContext context = new ResourceContext(Objects.requireNonNull(caller, "caller"),
                Objects.requireNonNull(tool, "tool"), java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(arguments)));
        CURRENT.set(context);
        return context;
    }

    /** The context of the call running on this thread. */
    public static Optional<ResourceContext> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    static ResourceContext require() {
        return current().orElseThrow(() -> new IllegalStateException(
                "resource checks are available only while a tool runs (no call in progress on this thread)"));
    }

    public CallerContext caller() {
        return caller;
    }

    public String tool() {
        return tool;
    }

    /** The arguments of the call as the client sent them (after validation). */
    public Map<String, Object> arguments() {
        return arguments;
    }

    /**
     * What the resolver of {@code type} fetched for {@code id} earlier in this call, for a tool that needs the same data
     * (the issue behind an argument, say) without calling the system again.
     *
     * @return empty when it was not fetched, did not exist, or is of another class
     */
    public <T> Optional<T> fetched(String type, String id, Class<T> as) {
        Resolved entry = resolved.get(type + "\u0000" + id);
        return entry == null || !as.isInstance(entry.fetched()) ? Optional.empty() : Optional.of(as.cast(entry.fetched()));
    }

    Optional<Resolved> cached(String type, String id) {
        return Optional.ofNullable(resolved.get(type + "\u0000" + id));
    }

    void remember(String type, String id, Resolved entry) {
        resolved.put(type + "\u0000" + id, entry);
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
