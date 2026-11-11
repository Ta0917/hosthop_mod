package dev.hosthop.lease;

/**
 * Local deadline calculation only; not a distributed lease or a host stop hook.
 * The caller must record both clocks before sending the request and must check
 * the deadline before every protected action. Server-clock bounds and actual
 * host shutdown are separate, currently unimplemented requirements.
 */
public final class ConservativeLeaseDeadline {
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long MAX_LEASE_SECONDS = 300L;

    private final long deadlineNanos;
    private final long deadlineMillis;
    private long lastNanos;
    private long lastMillis;
    private boolean expired;

    private ConservativeLeaseDeadline(long requestNanos, long requestMillis, long safeSeconds) {
        deadlineNanos = Math.addExact(requestNanos, Math.multiplyExact(safeSeconds, NANOS_PER_SECOND));
        deadlineMillis = Math.addExact(requestMillis, Math.multiplyExact(safeSeconds, MILLIS_PER_SECOND));
        lastNanos = requestNanos;
        lastMillis = requestMillis;
    }

    /**
     * The server timestamps must come from the same atomic grant. Subtract one
     * second for integer timestamp granularity plus the caller's safety margin.
     * Request start, not response arrival, is used to include network delay.
     */
    public static ConservativeLeaseDeadline fromGrant(long requestNanos, long requestMillis,
                                                       long serverNowSeconds, long serverExpirySeconds,
                                                       long safetyMarginSeconds) {
        if (safetyMarginSeconds < 0 || safetyMarginSeconds > MAX_LEASE_SECONDS) {
            throw new IllegalArgumentException("Invalid safety margin");
        }
        long remaining = Math.subtractExact(serverExpirySeconds, serverNowSeconds);
        if (remaining < 0 || remaining > MAX_LEASE_SECONDS) {
            throw new IllegalArgumentException("Invalid server lease interval");
        }
        long safeSeconds = Math.max(0, remaining - 1 - safetyMarginSeconds);
        return new ConservativeLeaseDeadline(requestNanos, requestMillis, safeSeconds);
    }

    /** Whether the deadline still has at least this much time left; does not record the reading. */
    public synchronized boolean usableFor(long nowNanos, long nowMillis, long seconds) {
        if (expired || seconds < 0 || nowNanos < lastNanos || nowMillis < lastMillis) return false;
        return deadlineNanos - nowNanos > seconds * NANOS_PER_SECOND && deadlineMillis - nowMillis > seconds * MILLIS_PER_SECOND;
    }

    /** Once a deadline expires or either clock goes backwards, it stays invalid. */
    public synchronized boolean isUsable(long nowNanos, long nowMillis) {
        if (expired) return false;
        if (nowNanos < lastNanos || nowMillis < lastMillis ||
                nowNanos >= deadlineNanos || nowMillis >= deadlineMillis) {
            expired = true;
            return false;
        }
        lastNanos = nowNanos;
        lastMillis = nowMillis;
        return true;
    }
}
