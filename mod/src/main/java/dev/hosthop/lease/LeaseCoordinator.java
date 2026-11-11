package dev.hosthop.lease;

import java.io.IOException;
import java.util.function.LongSupplier;

/** Blocking integration of lease transport and gate; caller runs it off the game thread. */
public final class LeaseCoordinator {
    private static final String ID = "[A-Za-z0-9_-]{1,80}";
    private final LocalHostGate gate;
    private final LeaseAuthorityClient client;
    private final LongSupplier nanos;
    private final LongSupplier millis;

    /** A retry is bound to the original world, lease ID, owner and duration. */
    public static final class Acquisition {
        private final LeaseCoordinator owner;
        private final LocalHostGate.Attempt attempt;
        private final String world, lease, principal;
        private final int duration;
        private final long margin;
        private volatile long epoch;
        private volatile long generation = -1;

        private Acquisition(LeaseCoordinator owner, LocalHostGate.Attempt attempt, String world, String lease,
                            String principal, int duration, long margin) {
            this.owner = owner; this.attempt = attempt; this.world = world; this.lease = lease;
            this.principal = principal; this.duration = duration; this.margin = margin;
        }
        public LocalHostGate.Attempt attempt() { return attempt; }
        public String world() { return world; }
        public String lease() { return lease; }
        public String principal() { return principal; }
        public long epoch() { return epoch; }
        public long generation() { return generation; }
    }

    public LeaseCoordinator(LocalHostGate gate, LeaseAuthorityClient client, LongSupplier nanos, LongSupplier millis) {
        if (gate == null || client == null || nanos == null || millis == null) throw new IllegalArgumentException("Missing coordinator input");
        this.gate = gate; this.client = client; this.nanos = nanos; this.millis = millis;
    }

    public Acquisition begin(String world, String lease, String principal, int duration, long margin) {
        if (world == null || !world.matches(ID) || lease == null || !lease.matches(ID)
                || principal == null || !principal.matches(ID) || duration < 1 || duration > 300 || margin < 0 || margin > 300)
            throw new IllegalArgumentException("Invalid acquisition");
        return new Acquisition(this, gate.beginAcquire(), world, lease, principal, duration, margin);
    }

    /** On transport failure, keep this acquisition unresolved and look up or retry the same ID. */
    public boolean acquire(Acquisition acquisition) throws IOException, InterruptedException {
        checkOwner(acquisition);
        if (!gate.isAcquiring(acquisition.attempt)) return false;
        var grant = client.acquire(acquisition.world, acquisition.lease, acquisition.duration, acquisition.margin);
        return accept(acquisition, grant);
    }

    public boolean recoverAcquisition(Acquisition acquisition) throws IOException, InterruptedException {
        checkOwner(acquisition);
        if (!gate.isAcquiring(acquisition.attempt)) return false;
        var grant = client.lookupAcquisition(acquisition.world, acquisition.lease, acquisition.duration, acquisition.principal, acquisition.margin);
        return accept(acquisition, grant);
    }

    private boolean accept(Acquisition acquisition, LeaseAuthorityClient.Grant grant) {
        if (!gate.acceptGrant(acquisition.attempt, grant.epoch(), grant.generation(), grant.deadline(), nanos.getAsLong(), millis.getAsLong())) return false;
        acquisition.generation = grant.generation();
        acquisition.epoch = grant.epoch();
        return true;
    }

    /** The caller must enforce STOP_REQUIRED promptly if renewal fails. */
    public boolean renew(Acquisition acquisition) throws IOException, InterruptedException {
        checkOwner(acquisition);
        if (acquisition.epoch < 1) return false;
        var renewal = gate.beginRenewal(acquisition.attempt, nanos.getAsLong(), millis.getAsLong());
        if (renewal == null) return false;
        try {
            var grant = client.renew(acquisition.world, acquisition.lease, acquisition.duration, acquisition.epoch, acquisition.margin);
            return gate.acceptRenewal(acquisition.attempt, renewal, grant.epoch(), grant.generation(), grant.deadline(), nanos.getAsLong(), millis.getAsLong());
        } catch (IOException | InterruptedException | RuntimeException failure) {
            gate.renewalFailed(acquisition.attempt, renewal);
            throw failure;
        }
    }

    /**
     * Like {@link #renew}, but a transport failure (timeout, connection loss, 5xx, 408/429) keeps the
     * current deadline instead of requiring a stop. A rejection by the authority still stops the host.
     * The caller must stop the host itself once {@link LocalHostGate#leaseUsableFor} turns false.
     */
    public boolean renewTolerant(Acquisition acquisition) throws IOException, InterruptedException {
        checkOwner(acquisition);
        if (acquisition.epoch < 1) return false;
        var renewal = gate.beginRenewal(acquisition.attempt, nanos.getAsLong(), millis.getAsLong());
        if (renewal == null) return false;
        try {
            var grant = client.renew(acquisition.world, acquisition.lease, acquisition.duration, acquisition.epoch, acquisition.margin);
            return gate.acceptRenewal(acquisition.attempt, renewal, grant.epoch(), grant.generation(), grant.deadline(), nanos.getAsLong(), millis.getAsLong());
        } catch (LeaseAuthorityClient.LeaseRejected rejected) {
            gate.renewalFailed(acquisition.attempt, renewal);
            throw rejected;
        } catch (IOException transportFailure) {
            gate.renewalDeferred(acquisition.attempt, renewal);
            throw transportFailure;
        } catch (InterruptedException | RuntimeException failure) {
            gate.renewalFailed(acquisition.attempt, renewal);
            throw failure;
        }
    }

    /** Retryable only before the host starts; a lost acknowledgement leaves ABORTING. */
    public boolean abortBeforeHosting(Acquisition acquisition) throws IOException, InterruptedException {
        checkOwner(acquisition);
        if (acquisition.epoch < 1 || !gate.beginAbort(acquisition.attempt)) return false;
        client.abort(acquisition.world, acquisition.principal, acquisition.lease, acquisition.epoch, acquisition.generation);
        return gate.abortConfirmed(acquisition.attempt);
    }

    private void checkOwner(Acquisition acquisition) {
        if (acquisition == null || acquisition.owner != this) throw new IllegalArgumentException("Unknown acquisition");
    }
}
