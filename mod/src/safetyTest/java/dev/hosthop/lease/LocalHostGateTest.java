package dev.hosthop.lease;

public final class LocalHostGateTest {
    public static void main(String[] args) {
        requiresVerifiedGenerationAndLiveLease();
        validRenewalExtendsHostingAndRejectsOlderResponse();
        expiredRenewalCannotReviveHost();
        lateCallbacksCannotRestartAStoppedAttempt();
        System.out.println("LocalHostGate safety checks passed");
    }

    private static ConservativeLeaseDeadline deadline() {
        return ConservativeLeaseDeadline.fromGrant(0, 0, 100, 130, 2);
    }

    private static void requiresVerifiedGenerationAndLiveLease() {
        var gate = new LocalHostGate();
        var attempt = gate.beginAcquire();
        check(gate.acceptGrant(attempt, 1, 5, deadline(), 0, 0), "grant rejected");
        check(!gate.beginHosting(attempt, 0, 0), "unverified snapshot started");
        check(!gate.snapshotVerified(attempt, 4, 0, 0), "stale generation accepted");
        check(gate.snapshotVerified(attempt, 5, 0, 0), "correct generation rejected");
        check(gate.beginHosting(attempt, 0, 0), "host did not start");
        check(!gate.mayContinueHosting(attempt, 27_000_000_000L, 27_000), "expired host allowed to continue");
        check(gate.phase() == LocalHostGate.Phase.STOP_REQUIRED, "stop not required");
        expectAcquireFailure(gate);
        check(gate.confirmStopped(attempt), "confirmed stop rejected");
        gate.beginAcquire();
    }

    private static void expiredRenewalCannotReviveHost() {
        var gate = new LocalHostGate();
        var attempt = gate.beginAcquire();
        check(gate.acceptGrant(attempt, 2, 1, deadline(), 0, 0), "grant rejected");
        check(gate.snapshotVerified(attempt, 1, 0, 0), "snapshot rejected");
        check(gate.beginHosting(attempt, 0, 0), "host start failed");
        var renewal = gate.beginRenewal(attempt, 1_000_000_000L, 1000);
        var newDeadline = ConservativeLeaseDeadline.fromGrant(26_000_000_000L, 26_000, 126, 156, 2);
        check(!gate.acceptRenewal(attempt, renewal, 2, newDeadline, 27_000_000_000L, 27_000), "late renewal revived expired host");
        check(gate.phase() == LocalHostGate.Phase.STOP_REQUIRED, "late renewal did not require stop");
        check(!gate.mayContinueHosting(attempt, 28_000_000_000L, 28_000), "expired host remained active");
    }

    private static void validRenewalExtendsHostingAndRejectsOlderResponse() {
        var gate = new LocalHostGate();
        var attempt = gate.beginAcquire();
        check(gate.acceptGrant(attempt, 3, 2, deadline(), 0, 0), "grant rejected");
        check(gate.snapshotVerified(attempt, 2, 0, 0), "snapshot rejected");
        check(gate.beginHosting(attempt, 0, 0), "host start failed");
        var older = gate.beginRenewal(attempt, 5_000_000_000L, 5000);
        var latest = gate.beginRenewal(attempt, 5_000_000_000L, 5000);
        var extension = ConservativeLeaseDeadline.fromGrant(5_000_000_000L, 5000, 105, 135, 2);
        check(!gate.acceptRenewal(attempt, older, 3, extension, 6_000_000_000L, 6000), "outdated renewal accepted");
        check(gate.acceptRenewal(attempt, latest, 3, extension, 6_000_000_000L, 6000), "valid renewal rejected");
        check(gate.mayContinueHosting(attempt, 30_000_000_000L, 30_000), "valid extension did not extend lease");
        check(!gate.mayContinueHosting(attempt, 32_000_000_000L, 32_000), "extended deadline did not expire");
    }

    private static void lateCallbacksCannotRestartAStoppedAttempt() {
        var gate = new LocalHostGate();
        var old = gate.beginAcquire();
        check(gate.requireStop(old), "stop request failed");
        check(!gate.acceptGrant(old, 1, 0, deadline(), 0, 0), "late grant accepted");
        expectAcquireFailure(gate);
        check(gate.confirmStopped(old), "stop confirmation failed");
        var current = gate.beginAcquire();
        check(!gate.acceptGrant(old, 1, 0, deadline(), 0, 0), "old attempt accepted after replacement");
        check(!gate.requireStop(old), "old attempt stopped current attempt");
        check(gate.acceptGrant(current, 2, 0, deadline(), 0, 0), "current grant rejected");
    }

    private static void expectAcquireFailure(LocalHostGate gate) {
        try {
            gate.beginAcquire();
            throw new AssertionError("acquisition accepted before stop confirmation");
        } catch (IllegalStateException expected) {
            // Expected failure.
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
