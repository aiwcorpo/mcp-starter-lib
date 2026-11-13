package pl.aiwcorpo.mcp.platform.server;

import io.modelcontextprotocol.spec.McpStreamableServerSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import pl.aiwcorpo.mcp.platform.auth.InternalJwtFilter;

/**
 * Closes MCP sessions that no request has used for {@code aiwcorpo.mcp.platform.session.idle-timeout}. The Spring AI transport
 * keeps a session until a DELETE or shutdown, and the gateway forgets an idle upstream session without sending DELETE, so
 * without this every abandoned session would stay in the heap until the server restarts. A request for an expired session
 * gets 404 from the transport, and the gateway answers that by initializing a new one.
 *
 * <p>Activity is a request that carries the session id and passed the internal JWT check (this filter runs after it), so a
 * caller without a token cannot keep a session alive. The transport has no public way to drop a session other than an HTTP
 * DELETE, hence the reflective access to its map; the Spring AI version is fixed by the platform BOM, and a version without
 * the field stops the start instead of leaking silently.
 */
final class IdleSessionSweeper extends OncePerRequestFilter implements Ordered, SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(IdleSessionSweeper.class);

    /** After the internal JWT filter and the policy data gate, before the credential filter. */
    static final int ORDER = InternalJwtFilter.ORDER + 2;

    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final Supplier<WebMvcStreamableServerTransportProvider> transport;
    private final long idleNanos;
    private final long periodMillis;
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private volatile Map<String, McpStreamableServerSession> sessions;
    private ScheduledExecutorService scheduler;

    /** @param transport the streamable transport, or {@code null} from the supplier in a context without one */
    IdleSessionSweeper(Supplier<WebMvcStreamableServerTransportProvider> transport, Duration idleTimeout) {
        if (idleTimeout == null || idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("aiwcorpo.mcp.platform.session.idle-timeout must be positive, got " + idleTimeout);
        }
        this.transport = transport;
        this.idleNanos = idleTimeout.toNanos();
        this.periodMillis = Math.clamp(idleTimeout.toMillis() / 4, 100L, 60_000L);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = request.getHeader(SESSION_HEADER);
        if (id != null && sessions != null) {
            // Only ids the sweep has already seen in the transport: a made-up id must not add an entry.
            lastSeen.computeIfPresent(id, (key, previous) -> System.nanoTime());
        }
        chain.doFilter(request, response);
    }

    void sweep() {
        long now = System.nanoTime();
        for (Map.Entry<String, McpStreamableServerSession> entry : sessions.entrySet()) {
            String id = entry.getKey();
            Long seen = lastSeen.putIfAbsent(id, now);
            if (seen != null && now - seen >= idleNanos && sessions.remove(id, entry.getValue())) {
                lastSeen.remove(id);
                entry.getValue().closeGracefully().onErrorComplete().subscribe();
                LOG.debug("event=mcp_session_expired session={}", id);
            }
        }
        lastSeen.keySet().retainAll(sessions.keySet());
    }

    @Override
    public synchronized void start() {
        WebMvcStreamableServerTransportProvider provider = transport.get();
        if (provider == null) {
            return;
        }
        sessions = sessionsOf(provider);
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mcp-session-sweeper");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                sweep();
            } catch (RuntimeException ex) {
                LOG.warn("event=mcp_session_sweep_failed error={}", ex.toString());
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return scheduler != null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, McpStreamableServerSession> sessionsOf(WebMvcStreamableServerTransportProvider transport) {
        try {
            Field field = WebMvcStreamableServerTransportProvider.class.getDeclaredField("sessions");
            field.setAccessible(true);
            return (Map<String, McpStreamableServerSession>) field.get(transport);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            throw new IllegalStateException("cannot reach the sessions of " + WebMvcStreamableServerTransportProvider.class.getName()
                    + "; this Spring AI version is not the one of the platform BOM", ex);
        }
    }
}
