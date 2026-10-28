package pl.aiwcorpo.mcp.platform.auth;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.LongSupplier;

/**
 * One per-process budget for the refusals of requests that carry no usable token: {@code missing_token} and
 * {@code malformed} (an empty or foreign {@code Authorization} header, a string that is not a JWT). Both cost the
 * caller nothing, so without a cap anyone who reaches the port could fill the audit stream and the application log
 * (CC-120). What is over the budget is only counted ({@value #METRIC}, tag {@code reason}), neither audited nor
 * logged. The same mechanism and default as the gateway's, which caps {@code missing_token}.
 *
 * <p>Other reasons are not capped: they need a token of the right shape, a certificate or a failing dependency, and
 * each of them is a signal on its own.
 */
public final class MissingTokenEventLimiter {

    public static final int DEFAULT_PER_SECOND = 20;
    public static final String METRIC = "mcp_server_audit_authentication_suppressed_total";

    private static final long SECOND_NANOS = 1_000_000_000L;

    private final int perSecond;
    private final LongSupplier nanoClock;
    private final MeterRegistry meters;
    private long windowStart;
    private int inWindow;

    public MissingTokenEventLimiter(int perSecond, LongSupplier nanoClock, MeterRegistry meters) {
        if (perSecond < 1) {
            throw new IllegalArgumentException("perSecond must be at least 1, got " + perSecond);
        }
        this.perSecond = perSecond;
        this.nanoClock = nanoClock;
        this.meters = meters;
        this.windowStart = nanoClock.getAsLong();
    }

    /** Whether a refusal for this reason is capped at all. */
    public static boolean covers(InternalJwtRejection reason) {
        return reason == InternalJwtRejection.MISSING_TOKEN || reason == InternalJwtRejection.MALFORMED;
    }

    /** True when the event may be written; false (and counted under {@code reason}) when this second's budget is spent. */
    public synchronized boolean tryAcquire(InternalJwtRejection reason) {
        long now = nanoClock.getAsLong();
        if (now - windowStart >= SECOND_NANOS) {
            windowStart = now;
            inWindow = 0;
        }
        if (++inWindow > perSecond) {
            meters.counter(METRIC, "reason", reason.code()).increment();
            return false;
        }
        return true;
    }
}
