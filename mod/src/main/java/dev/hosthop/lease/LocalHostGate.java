package dev.hosthop.lease;

/**
 * Local lifecycle guard only. It neither starts nor stops Minecraft, verifies
 * storage, authenticates a grant, nor fences another computer. Every caller
 * must obtain trusted evidence and enforce STOP_REQUIRED outside this class.
 */
public final class LocalHostGate {
    public enum Phase { IDLE, ACQUIRING, PREPARING, ABORTING, HOSTING, QUIESCING, SNAPSHOTTING, PUBLISHING, RELEASE_REQUIRED, STOP_REQUIRED }

    public static final class Attempt { private Attempt() {} }
    public static final class Renewal { private Renewal() {} }

    private Phase phase = Phase.IDLE;
    private Attempt current;
    private Renewal pendingRenewal;
    private ConservativeLeaseDeadline deadline;
    private long epoch;
    private long generation;
    private boolean snapshotVerified;
    private boolean everHosted;
    private boolean publicationUncertain;
    private PublicationRecord publication;

    public synchronized Attempt beginAcquire() {
        if (phase != Phase.IDLE) throw new IllegalStateException("Prior host is not confirmed stopped");
        current = new Attempt();
        phase = Phase.ACQUIRING;
        return current;
    }

    public synchronized boolean isAcquiring(Attempt attempt) {
        return attempt != null && attempt == current && phase == Phase.ACQUIRING;
    }

    /**
     * Restore an unresolved, locally persisted request before allowing acquisition.
     * The caller must discover journals on startup and authenticate any receipt.
     */
    public synchronized Attempt beginRecovery(PublicationRecord pending) {
        if (phase != Phase.IDLE) throw new IllegalStateException("A host attempt is already active");
        if (pending == null) throw new IllegalArgumentException("Missing recovery record");
        current = new Attempt();
        publication = pending;
        publicationUncertain = true;
        phase = Phase.STOP_REQUIRED;
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
        everHosted = false;
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
        everHosted = true;
        phase = Phase.HOSTING;
        return true;
    }

    /** Only a host which has not started may abandon an acquisition. */
    public synchronized boolean beginAbort(Attempt attempt) {
        if (attempt == null || attempt != current || everHosted || publicationUncertain
                || (phase != Phase.PREPARING && phase != Phase.ABORTING && phase != Phase.STOP_REQUIRED)) return false;
        phase = Phase.ABORTING;
        pendingRenewal = null;
        return true;
    }

    public synchronized boolean abortConfirmed(Attempt attempt) {
        if (attempt == null || attempt != current || phase != Phase.ABORTING) return false;
        clearAttempt();
        return true;
    }

    /** Returns null if the local host must not begin a renewal. */
    public synchronized Renewal beginRenewal(Attempt attempt, long nowNanos, long nowMillis) {
        if (attempt == null || attempt != current || !mayRenewInPhase()) return null;
        if (!checkDeadline(nowNanos, nowMillis)) return null;
        pendingRenewal = new Renewal();
        return pendingRenewal;
    }

    /** A renewal cannot revive an expired lease or a stopped/replaced attempt. */
    public synchronized boolean acceptRenewal(Attempt attempt, Renewal renewal, long renewedEpoch, long renewedGeneration,
                                               ConservativeLeaseDeadline renewed, long nowNanos, long nowMillis) {
        if (attempt == null || attempt != current || renewal == null || renewal != pendingRenewal ||
                !mayRenewInPhase()) return false;
        if (renewedEpoch != epoch || renewedGeneration != generation || renewed == null) {
            pendingRenewal = null;
            phase = Phase.STOP_REQUIRED;
            return false;
        }
        pendingRenewal = null;
        if (!checkDeadline(nowNanos, nowMillis)) return false;
        if (!renewed.isUsable(nowNanos, nowMillis)) {
            phase = Phase.STOP_REQUIRED;
            return false;
        }
        deadline = renewed;
        return true;
    }

    /** A failed superseded request must not stop a newer successful renewal. */
    public synchronized boolean renewalFailed(Attempt attempt, Renewal renewal) {
        if (attempt == null || attempt != current || renewal == null || renewal != pendingRenewal || !mayRenewInPhase()) return false;
        pendingRenewal = null;
        phase = Phase.STOP_REQUIRED;
        return true;
    }

    /** Must be called by the host loop before each protected action. */
    public synchronized boolean mayContinueHosting(Attempt attempt, long nowNanos, long nowMillis) {
        return attempt != null && attempt == current && phase == Phase.HOSTING && checkDeadline(nowNanos, nowMillis);
    }

    /** Start an orderly shutdown while retaining the lease for the final snapshot. */
    public synchronized boolean beginGracefulStop(Attempt attempt, long nowNanos, long nowMillis) {
        if (attempt == null || attempt != current || phase != Phase.HOSTING) return false;
        if (!checkDeadline(nowNanos, nowMillis)) return false;
        phase = Phase.QUIESCING;
        return true;
    }

    /** Caller must have fully stopped the server and external relay first. */
    public synchronized boolean serverStoppedForSnapshot(Attempt attempt, long nowNanos, long nowMillis) {
        if (attempt == null || attempt != current || phase != Phase.QUIESCING) return false;
        if (!checkDeadline(nowNanos, nowMillis)) return false;
        phase = Phase.SNAPSHOTTING;
        return true;
    }

    /** Caller must create and verify a consistent snapshot against this base generation. */
    public synchronized boolean finalSnapshotVerified(Attempt attempt, long baseGeneration, long nowNanos, long nowMillis) {
        if (attempt == null || attempt != current || phase != Phase.SNAPSHOTTING) return false;
        if (!checkDeadline(nowNanos, nowMillis) || baseGeneration != generation) return false;
        phase = Phase.PUBLISHING;
        return true;
    }

    /** The remote commit must independently fence the lease and expected generation. */
    public synchronized boolean mayPublish(Attempt attempt, PublicationRecord operation, long nowNanos, long nowMillis) {
        if (attempt == null || attempt != current || phase != Phase.PUBLISHING || !checkDeadline(nowNanos, nowMillis)) return false;
        if (operation == null || operation.epoch() != epoch || operation.previousGeneration() != generation
                || (publication != null && !publication.equals(operation))) return false;
        // Once a request may be sent, a lost response requires authoritative reconciliation.
        publication = operation;
        publicationUncertain = true;
        return true;
    }

    /** Caller must authenticate and match the acknowledgement to its operation. */
    public synchronized boolean publicationConfirmed(Attempt attempt, PublicationRecord receipt, long nowNanos, long nowMillis) {
        if (attempt == null || attempt != current || phase != Phase.PUBLISHING || !publicationUncertain) return false;
        if (!checkDeadline(nowNanos, nowMillis) || !publication.equals(receipt)) return false;
        generation = receipt.resultGeneration();
        publicationUncertain = false;
        pendingRenewal = null;
        phase = Phase.RELEASE_REQUIRED;
        return true;
    }

    /** Caller must authenticate the authority's release record. */
    public synchronized boolean confirmReleased(Attempt attempt, ReleaseRecord receipt) {
        if (attempt == null || attempt != current || phase != Phase.RELEASE_REQUIRED) return false;
        if (receipt == null || !receipt.matches(publication)) return false;
        clearAttempt();
        return true;
    }

    public synchronized boolean awaitingRelease(Attempt attempt, PublicationRecord operation) {
        return attempt != null && attempt == current && phase == Phase.RELEASE_REQUIRED && publication != null && publication.equals(operation);
    }

    public synchronized boolean awaitingReconciliation(Attempt attempt, PublicationRecord operation) {
        return attempt != null && attempt == current && phase == Phase.STOP_REQUIRED && publicationUncertain
                && publication != null && publication.equals(operation);
    }

    public synchronized boolean requireStop(Attempt attempt) {
        if (attempt == null || attempt != current || phase == Phase.IDLE || phase == Phase.RELEASE_REQUIRED) return false;
        phase = Phase.STOP_REQUIRED;
        pendingRenewal = null;
        return true;
    }

    /** Caller must reconcile the operation against authenticated authoritative records first. */
    public synchronized boolean publicationReconciled(Attempt attempt, PublicationRecord receipt) {
        if (attempt == null || attempt != current || phase != Phase.STOP_REQUIRED || !publicationUncertain) return false;
        if (!publication.equals(receipt)) return false;
        publicationUncertain = false;
        return true;
    }

    /** Caller must actually stop the server and relay before confirming. */
    public synchronized boolean confirmStopped(Attempt attempt) {
        if (attempt == null || attempt != current || phase != Phase.STOP_REQUIRED || publicationUncertain) return false;
        clearAttempt();
        return true;
    }

    private void clearAttempt() {
        current = null;
        pendingRenewal = null;
        deadline = null;
        snapshotVerified = false;
        everHosted = false;
        publicationUncertain = false;
        publication = null;
        phase = Phase.IDLE;
    }

    public synchronized Phase phase() { return phase; }

    private boolean mayRenewInPhase() {
        return phase == Phase.PREPARING || phase == Phase.HOSTING || phase == Phase.QUIESCING ||
                phase == Phase.SNAPSHOTTING || phase == Phase.PUBLISHING;
    }

    private boolean checkDeadline(long nowNanos, long nowMillis) {
        if (deadline != null && deadline.isUsable(nowNanos, nowMillis)) return true;
        phase = Phase.STOP_REQUIRED;
        pendingRenewal = null;
        return false;
    }
}
