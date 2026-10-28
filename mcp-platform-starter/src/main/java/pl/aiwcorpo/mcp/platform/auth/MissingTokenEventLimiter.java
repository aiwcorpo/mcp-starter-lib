package pl.aiwcorpo.mcp.platform.auth;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-process cap on {@code missing_token} authentication events (C8, CC-120): a request without a token costs the
 * caller nothing, so without a cap anyone who reaches the port could fill the audit stream. Events over the cap are
 * only counted ({@value #METRIC}, tag {@code reason}), never written. The same mechanism and default as the
 * gateway's. Other reasons are not capped: they need a certificate, a token of a known shape or a failing dependency,
 * and each of them is a signal on its own.
 */
public final class MissingTokenEventLimiter {

    public static final int DEFAULT_PER_SECOND = 20;
    public static final String METRIC = "mcp_server_audit_authentication_suppressed_total";

    private static final long SECOND_NANOS = 1_000_000_000L;

    private final int perSecond;
    private final LongSupplier nanoClock;
    private final Counter suppressed;
    private final AtomicLong windowStart;
    private final AtomicLong inWindow = new AtomicLong();

    public MissingTokenEventLimiter(int perSecond, LongSupplier nanoClock, MeterRegistry meters) {
        if (perSecond < 1) {
            throw new IllegalArgumentException("perSecond must be at least 1, got " + perSecond);
        }
        this.perSecond = perSecond;
        this.nanoClock = nanoClock;
        this.suppressed = Counter.builder(METRIC).tag("reason", InternalJwtRejection.MISSING_TOKEN.code())
                .description("missing_token authentication events not written to the audit log (rate cap)")
                .register(meters);
        this.windowStart = new AtomicLong(nanoClock.getAsLong());
    }

    /** True when the event may be written; false (and counted) when this second's budget is spent. */
    public boolean tryAcquire() {
        long now = nanoClock.getAsLong();
        long start = windowStart.get();
        if (now - start >= SECOND_NANOS && windowStart.compareAndSet(start, now)) {
            inWindow.set(0);
        }
        if (inWindow.incrementAndGet() > perSecond) {
            suppressed.increment();
            return false;
        }
        return true;
    }
}
