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
        var acquisition = coordinator.begin("world", "lease", "owner", 30, 2);
        var attempt = acquisition.attempt();
        var otherCoordinator = new LeaseCoordinator(new LocalHostGate(), client, () -> 0, () -> 0);
        try { otherCoordinator.acquire(acquisition); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        try { coordinator.acquire(acquisition); throw new AssertionError(); }
        catch (IOException expected) { }
        check(gate.phase() == LocalHostGate.Phase.ACQUIRING);
        response.set("{\"owner\":\"owner\",\"epoch\":4,\"base_generation\":3,\"duration_seconds\":30,\"active\":1,\"server_now\":100,\"expires\":130}");
        check(coordinator.recoverAcquisition(acquisition));
        check(gate.snapshotVerified(attempt, 3, 0, 0) && gate.beginHosting(attempt, 0, 0));
        response.set("{\"epoch\":4,\"generation\":3,\"server_now\":100,\"expires\":130}");
        check(coordinator.renew(acquisition));
        check(gate.phase() == LocalHostGate.Phase.HOSTING);
        response.set(null);
        try { coordinator.renew(acquisition); throw new AssertionError(); }
        catch (IOException expected) { }
        check(gate.phase() == LocalHostGate.Phase.STOP_REQUIRED);
        check(!coordinator.acquire(acquisition));

        // Tolerant renewal: a lost response keeps hosting on the current lease; a refusal stops it.
        var status = new java.util.concurrent.atomic.AtomicInteger(200);
        var tolerantClient = new LeaseAuthorityClient(URI.create("https://authority.example"), "a".repeat(43), request -> {
            if (response.get() == null) throw new IOException("simulated connection loss");
            return new LeaseAuthorityClient.Reply(status.get(), response.get());
        }, () -> 0, () -> 0);
        var tolerantGate = new LocalHostGate();
        var tolerant = new LeaseCoordinator(tolerantGate, tolerantClient, () -> 0, () -> 0);
        var hosted = tolerant.begin("world", "lease2", "owner", 30, 2);
        response.set("{\"epoch\":5,\"generation\":3,\"server_now\":100,\"expires\":130}");
        check(tolerant.acquire(hosted));
        check(tolerantGate.snapshotVerified(hosted.attempt(), 3, 0, 0) && tolerantGate.beginHosting(hosted.attempt(), 0, 0));
        check(tolerantGate.leaseUsableFor(hosted.attempt(), 20, 0, 0) && !tolerantGate.leaseUsableFor(hosted.attempt(), 27, 0, 0));
        response.set(null);
        try { tolerant.renewTolerant(hosted); throw new AssertionError(); } catch (IOException expected) { }
        check(tolerantGate.phase() == LocalHostGate.Phase.HOSTING && tolerantGate.mayContinueHosting(hosted.attempt(), 0, 0));
        status.set(503); response.set("{}");
        try { tolerant.renewTolerant(hosted); throw new AssertionError(); } catch (IOException expected) { }
        check(tolerantGate.phase() == LocalHostGate.Phase.HOSTING);
        status.set(200); response.set("{\"epoch\":5,\"generation\":3,\"server_now\":100,\"expires\":130}");
        check(tolerant.renewTolerant(hosted));
        status.set(409); response.set("{\"error\":\"lease conflict\"}");
        try { tolerant.renewTolerant(hosted); throw new AssertionError(); } catch (LeaseAuthorityClient.LeaseRejected expected) { }
        check(tolerantGate.phase() == LocalHostGate.Phase.STOP_REQUIRED);
        var lapsed = ConservativeLeaseDeadline.fromGrant(0, 0, 100, 130, 2);
        check(lapsed.usableFor(1_000_000_000L, 1_000, 20) && !lapsed.usableFor(8_000_000_000L, 8_000, 20) && lapsed.isUsable(1, 1));

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
        var abortGate = new LocalHostGate();
        var abortCoordinator = new LeaseCoordinator(abortGate, client, () -> 0, () -> 0);
        var abortAcquisition = abortCoordinator.begin("world", "lease", "owner", 30, 2);
        response.set("{\"epoch\":4,\"generation\":3,\"server_now\":100,\"expires\":130}");
        check(abortCoordinator.acquire(abortAcquisition));
        response.set(null);
        try { abortCoordinator.abortBeforeHosting(abortAcquisition); throw new AssertionError(); } catch (IOException expected) { }
        check(abortGate.phase() == LocalHostGate.Phase.ABORTING);
        check(!abortGate.beginHosting(abortAcquisition.attempt(), 0, 0));
        response.set("{\"world_id\":\"world\",\"owner\":\"owner\",\"lease_id\":\"lease\",\"epoch\":4,\"base_generation\":3}");
        check(abortCoordinator.abortBeforeHosting(abortAcquisition));
        check(abortGate.phase() == LocalHostGate.Phase.IDLE);
        System.out.println("LeaseCoordinator safety checks passed");
    }

    private static void check(boolean value) { if (!value) throw new AssertionError(); }
}
