package dev.hosthop.lease;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

public final class LeaseCoordinatorTest {
    public static void main(String[] args) throws Exception {
        var response = new AtomicReference<String>();
        var client = new LeaseAuthorityClient(URI.create("https://authority.example"), "a".repeat(43), request -> {
            if (response.get() == null) throw new IOException("simulated connection loss");
            return new LeaseAuthorityClient.Reply(200, response.get());
        }, () -> 0, () -> 0);
        var gate = new LocalHostGate();
        var coordinator = new LeaseCoordinator(gate, client, () -> 0, () -> 0);
        var attempt = gate.beginAcquire();
        try { coordinator.acquire(attempt, "world", "lease", 30, 2); throw new AssertionError(); }
        catch (IOException expected) { }
        check(gate.phase() == LocalHostGate.Phase.ACQUIRING);
        response.set("{\"owner\":\"owner\",\"epoch\":4,\"base_generation\":3,\"duration_seconds\":30,\"active\":1,\"server_now\":100,\"expires\":130}");
        check(coordinator.recoverAcquisition(attempt, "world", "lease", 30, "owner", 2));
        check(gate.snapshotVerified(attempt, 3, 0, 0) && gate.beginHosting(attempt, 0, 0));
        response.set("{\"epoch\":4,\"generation\":3,\"server_now\":100,\"expires\":130}");
        check(coordinator.renew(attempt, "world", "lease", 30, 4, 2));
        check(gate.phase() == LocalHostGate.Phase.HOSTING);
        response.set(null);
        try { coordinator.renew(attempt, "world", "lease", 30, 4, 2); throw new AssertionError(); }
        catch (IOException expected) { }
        check(gate.phase() == LocalHostGate.Phase.STOP_REQUIRED);

        var newer = new LocalHostGate();
        var a = newer.beginAcquire();
        var deadline = ConservativeLeaseDeadline.fromGrant(0, 0, 100, 130, 2);
        check(newer.acceptGrant(a, 4, 3, deadline, 0, 0));
        var oldRenewal = newer.beginRenewal(a, 0, 0);
        var latest = newer.beginRenewal(a, 0, 0);
        check(!newer.renewalFailed(a, oldRenewal));
        check(newer.acceptRenewal(a, latest, 4, 3, ConservativeLeaseDeadline.fromGrant(0, 0, 100, 130, 2), 0, 0));
        check(newer.phase() == LocalHostGate.Phase.PREPARING);
        var mismatch = newer.beginRenewal(a, 0, 0);
        check(!newer.acceptRenewal(a, mismatch, 4, 4, ConservativeLeaseDeadline.fromGrant(0, 0, 100, 130, 2), 0, 0));
        check(newer.phase() == LocalHostGate.Phase.STOP_REQUIRED);
        System.out.println("LeaseCoordinator safety checks passed");
    }

    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
