package pl.aiwcorpo.mcp.platform.auth;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Per-process caps on the refusals of requests whose token could not even be parsed: {@code missing_token} (no or empty
 * header) and {@code malformed} (a foreign {@code Authorization} scheme, a string that is not three segments, a header
 * that is not a JSON object). Both cost the caller nothing, so without a cap anyone who reaches the port could fill the
 * audit stream and the application log (CC-120). Each reason has a budget of its own: a flood of one must not use up the
 * other. What is over a budget is only counted ({@value #METRIC}, tags {@code component=server} and {@code reason}), neither audited nor logged.
 *
 * <p>Nothing else is capped, in particular not a token that got past parsing and the key lookup (a header with
 * {@code crit}, a wrong signature, claims): that takes effort and is an attempt, not noise.
 */
public final class MissingTokenEventLimiter {

    public static final int DEFAULT_PER_SECOND = 20;
    /** Name shared by every receiver (CC-143): the starter, the broker and the gateway differ in the {@code component} tag. */
    public static final String METRIC = "mcp_audit_authentication_suppressed_total";
    static final String COMPONENT = "server";

    private static final long SECOND_NANOS = 1_000_000_000L;

    private final int perSecond;
    private final LongSupplier nanoClock;
    private final MeterRegistry meters;
    private final Map<InternalJwtRejection, long[]> windows = new EnumMap<>(InternalJwtRejection.class);

    public MissingTokenEventLimiter(int perSecond, LongSupplier nanoClock, MeterRegistry meters) {
        if (perSecond < 1) {
            throw new IllegalArgumentException("perSecond must be at least 1, got " + perSecond);
        }
        this.perSecond = perSecond;
        this.nanoClock = nanoClock;
        this.meters = meters;
    }

    /** Whether a refusal for this reason is capped at all. */
    public static boolean covers(InternalJwtRejection reason) {
        return reason == InternalJwtRejection.MISSING_TOKEN || reason == InternalJwtRejection.MALFORMED;
    }

    /** True when the event may be written; false (and counted under {@code reason}) when this second's budget is spent. */
    public synchronized boolean tryAcquire(InternalJwtRejection reason) {
        long now = nanoClock.getAsLong();
        long[] window = windows.computeIfAbsent(reason, r -> new long[] {now, 0});
        if (now - window[0] >= SECOND_NANOS) {
            window[0] = now;
            window[1] = 0;
        }
        if (++window[1] > perSecond) {
            meters.counter(METRIC, "component", COMPONENT, "reason", reason.code()).increment();
            return false;
        }
        return true;
    }
}
