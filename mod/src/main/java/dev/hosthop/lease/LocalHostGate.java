package dev.hosthop.lease;

/**
 * Local lifecycle guard only. It neither starts nor stops Minecraft, verifies
 * storage, authenticates a grant, nor fences another computer. Every caller
 * must obtain trusted evidence and enforce STOP_REQUIRED outside this class.
 */
public final class LocalHostGate {
    public enum Phase { IDLE, ACQUIRING, PREPARING, HOSTING, STOP_REQUIRED }

    public static final class Attempt { private Attempt() {} }
    public static final class Renewal { private Renewal() {} }

    private Phase phase = Phase.IDLE;
    private Attempt current;
    private Renewal pendingRenewal;
    private ConservativeLeaseDeadline deadline;
    private long epoch;
    private long generation;
    private boolean snapshotVerified;

    public synchronized Attempt beginAcquire() {
        if (phase != Phase.IDLE) throw new IllegalStateException("Prior host is not confirmed stopped");
        current = new Attempt();
        phase = Phase.ACQUIRING;
        return current;
    }

    public synchronized boolean acceptGrant(Attempt attempt, long epoch, long generation,
                                            ConservativeLeaseDeadline grant, long nowNanos, long nowMillis) {
        if (attempt == null || attempt != current || phase != Phase.ACQUIRING) return false;
        if (epoch < 1 || generation < 0 || grant == null) throw new IllegalArgumentException("Invalid grant");
        if (!grant.isUsable(nowNanos, nowMillis)) {
            phase = Phase.STOP_REQUIRED;
            return false;
        }
        this.epoch = epoch;
        this.generation = generation;
        deadline = grant;
        snapshotVerified = false;
        phase = Phase.PREPARING;
        return true;
    }

    /** Caller must verify the complete snapshot before reporting its generation. */
    public synchronized boolean snapshotVerified(Attempt attempt, long verifiedGeneration, long nowNanos, long nowMillis) {
        if (attempt == null || attempt != current || phase != Phase.PREPARING) return false;
        if (!checkDeadline(nowNanos, nowMillis) || verifiedGeneration != generation) return false;
        snapshotVerified = true;
        return true;
    }

    public synchronized boolean beginHosting(Attempt attempt, long nowNanos, long nowMillis) {
        if (attempt == null || attempt != current || phase != Phase.PREPARING || !snapshotVerified) return false;
        if (!checkDeadline(nowNanos, nowMillis)) return false;
        phase = Phase.HOSTING;
        return true;
    }

    /** Returns null if the local host must not begin a renewal. */
    public synchronized Renewal beginRenewal(Attempt attempt, long nowNanos, long nowMillis) {
        if (attempt == null || attempt != current || (phase != Phase.PREPARING && phase != Phase.HOSTING)) return null;
        if (!checkDeadline(nowNanos, nowMillis)) return null;
        pendingRenewal = new Renewal();
        return pendingRenewal;
    }

    /** A renewal cannot revive an expired lease or a stopped/replaced attempt. */
    public synchronized boolean acceptRenewal(Attempt attempt, Renewal renewal, long renewedEpoch,
                                               ConservativeLeaseDeadline renewed, long nowNanos, long nowMillis) {
        if (attempt == null || attempt != current || renewal == null || renewal != pendingRenewal ||
                (phase != Phase.PREPARING && phase != Phase.HOSTING)) return false;
        if (renewedEpoch != epoch || renewed == null) return false;
        pendingRenewal = null;
        if (!checkDeadline(nowNanos, nowMillis)) return false;
        if (!renewed.isUsable(nowNanos, nowMillis)) {
            phase = Phase.STOP_REQUIRED;
            return false;
        }
        deadline = renewed;
        return true;
    }

    /** Must be called by the host loop before each protected action. */
    public synchronized boolean mayContinueHosting(Attempt attempt, long nowNanos, long nowMillis) {
        return attempt != null && attempt == current && phase == Phase.HOSTING && checkDeadline(nowNanos, nowMillis);
    }

    public synchronized boolean requireStop(Attempt attempt) {
        if (attempt == null || attempt != current || phase == Phase.IDLE) return false;
        phase = Phase.STOP_REQUIRED;
        pendingRenewal = null;
        return true;
    }

    /** Caller must actually stop the server and relay before confirming. */
    public synchronized boolean confirmStopped(Attempt attempt) {
        if (attempt == null || attempt != current || phase != Phase.STOP_REQUIRED) return false;
        current = null;
        pendingRenewal = null;
        deadline = null;
        snapshotVerified = false;
        phase = Phase.IDLE;
        return true;
    }

    public synchronized Phase phase() { return phase; }

    private boolean checkDeadline(long nowNanos, long nowMillis) {
        if (deadline != null && deadline.isUsable(nowNanos, nowMillis)) return true;
        phase = Phase.STOP_REQUIRED;
        pendingRenewal = null;
        return false;
    }
}
