package pl.aiwcorpo.mcp.platform.guard;

import java.util.Optional;

/**
 * Request-scoped holder for the resolved {@link CallerIdentity}.
 *
 * <p>A {@link ThreadLocal}, exactly like {@code CredentialContext}, and safe for the same reason: the
 * synchronous MCP server runs a tool on the thread that received the request. The same
 * {@code CredentialTransportGuard} that protects PATs therefore protects this too.
 */
public final class CallerContext {

    private static final ThreadLocal<CallerIdentity> CURRENT = new ThreadLocal<>();

    private CallerContext() {
    }

    public static void set(CallerIdentity identity) {
        CURRENT.set(identity);
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static Optional<CallerIdentity> current() {
        return Optional.ofNullable(CURRENT.get());
    }
}
