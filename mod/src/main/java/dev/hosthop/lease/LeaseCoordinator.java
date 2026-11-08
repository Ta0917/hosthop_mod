package dev.hosthop.lease;

import java.io.IOException;
import java.util.function.LongSupplier;

/** Blocking integration of lease transport and gate; caller runs it off the game thread. */
public final class LeaseCoordinator {
    private final LocalHostGate gate;
    private final LeaseAuthorityClient client;
    private final LongSupplier nanos;
    private final LongSupplier millis;

    public LeaseCoordinator(LocalHostGate gate, LeaseAuthorityClient client, LongSupplier nanos, LongSupplier millis) {
        if (gate == null || client == null || nanos == null || millis == null) throw new IllegalArgumentException("Missing coordinator input");
        this.gate = gate;
        this.client = client;
        this.nanos = nanos;
        this.millis = millis;
    }

    /** On transport failure, keep the attempt unresolved and look up the same lease ID. */
    public boolean acquire(LocalHostGate.Attempt attempt, String world, String lease, int duration, long margin)
            throws IOException, InterruptedException {
        var grant = client.acquire(world, lease, duration, margin);
        return gate.acceptGrant(attempt, grant.epoch(), grant.generation(), grant.deadline(), nanos.getAsLong(), millis.getAsLong());
    }

    public boolean recoverAcquisition(LocalHostGate.Attempt attempt, String world, String lease, int duration, String owner, long margin)
            throws IOException, InterruptedException {
        var grant = client.lookupAcquisition(world, lease, duration, owner, margin);
        return gate.acceptGrant(attempt, grant.epoch(), grant.generation(), grant.deadline(), nanos.getAsLong(), millis.getAsLong());
    }

    /** The caller must enforce STOP_REQUIRED promptly if renewal fails. */
    public boolean renew(LocalHostGate.Attempt attempt, String world, String lease, int duration, long epoch, long margin)
            throws IOException, InterruptedException {
        var renewal = gate.beginRenewal(attempt, nanos.getAsLong(), millis.getAsLong());
        if (renewal == null) return false;
        try {
            var grant = client.renew(world, lease, duration, epoch, margin);
            return gate.acceptRenewal(attempt, renewal, grant.epoch(), grant.generation(), grant.deadline(), nanos.getAsLong(), millis.getAsLong());
        } catch (IOException | InterruptedException | RuntimeException failure) {
            gate.renewalFailed(attempt, renewal);
            throw failure;
        }
    }
}
