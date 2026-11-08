package dev.hosthop.lease;

public final class LocalHostGateTest {
    public static void main(String[] args) throws Exception {
        requiresVerifiedGenerationAndLiveLease();
        validRenewalExtendsHostingAndRejectsOlderResponse();
        expiredRenewalCannotReviveHost();
        lateCallbacksCannotRestartAStoppedAttempt();
        gracefulPublicationRequiresOrderedEvidence();
        expiredPublicationRequiresReconciliation();
        publicationJournalPreservesEvidence();
        recoveryBlocksNewHostUntilReceiptMatches();
        preparationRecordsBeforeSendAndStopsOnFailure();
        System.out.println("LocalHostGate safety checks passed");
    }

    private static LocalHostGate.Attempt readyToPublish(LocalHostGate gate) {
        var attempt = gate.beginAcquire();
        check(gate.acceptGrant(attempt, 6, 11, deadline(), 0, 0), "grant rejected");
        check(gate.snapshotVerified(attempt, 11, 0, 0), "initial snapshot rejected");
        check(gate.beginHosting(attempt, 0, 0), "hosting rejected");
        check(gate.beginGracefulStop(attempt, 0, 0), "shutdown rejected");
        check(gate.serverStoppedForSnapshot(attempt, 0, 0), "stop rejected");
        check(gate.finalSnapshotVerified(attempt, 11, 0, 0), "final snapshot rejected");
        return attempt;
    }

    private static void preparationRecordsBeforeSendAndStopsOnFailure() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("hosthop-prepare-test-");
        var path = directory.resolve("pending");
        try {
            var operation = publication(6, 11, "op", "digest");
            var gate = new LocalHostGate();
            var attempt = readyToPublish(gate);
            check(PublicationPreparation.prepare(gate, attempt, path, operation, () -> 0, () -> 0), "preparation failed");
            check(operation.equals(PendingPublicationJournal.read(path)), "request not saved before send");
            check(PublicationPreparation.prepare(gate, attempt, path, operation, () -> 0, () -> 0), "identical retry rejected");

            var conflictingGate = new LocalHostGate();
            var conflictingAttempt = readyToPublish(conflictingGate);
            try {
                PublicationPreparation.prepare(conflictingGate, conflictingAttempt, path, publication(6, 11, "other", "digest"), () -> 0, () -> 0);
                throw new AssertionError("conflicting journal accepted");
            } catch (java.io.IOException expected) { }
            check(conflictingGate.phase() == LocalHostGate.Phase.STOP_REQUIRED, "journal conflict did not require stop");
            check(operation.equals(PendingPublicationJournal.read(path)), "journal conflict changed evidence");

            var expiryGate = new LocalHostGate();
            var expiryAttempt = readyToPublish(expiryGate);
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            check(!PublicationPreparation.prepare(expiryGate, expiryAttempt, path, operation,
                    () -> calls.getAndIncrement() == 0 ? 0 : 27_000_000_000L,
                    () -> calls.get() <= 1 ? 0 : 27_000), "lease expiry during preparation was ignored");
            check(expiryGate.phase() == LocalHostGate.Phase.STOP_REQUIRED, "expiry did not require stop");
        } finally {
            java.nio.file.Files.deleteIfExists(path);
            java.nio.file.Files.delete(directory);
        }
    }

    private static void recoveryBlocksNewHostUntilReceiptMatches() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("hosthop-recovery-test-");
        var path = directory.resolve("pending");
        try {
            var operation = publication(6, 11, "old-op", "digest");
            PendingPublicationJournal.create(path, operation);
            var restarted = new LocalHostGate();
            var recovery = restarted.beginRecovery(PendingPublicationJournal.read(path));
            expectAcquireFailure(restarted);
            check(!restarted.confirmStopped(recovery), "recovery allowed unverified stop");
            check(!restarted.publicationReconciled(recovery, publication(6, 11, "old-op", "wrong")), "recovery accepted wrong receipt");
            check(restarted.publicationReconciled(recovery, operation), "matching recovery rejected");
            check(restarted.confirmStopped(recovery), "recovery could not finish");
            check(restarted.beginAcquire() != null, "acquisition unavailable after recovery");
        } finally {
            java.nio.file.Files.deleteIfExists(path);
            java.nio.file.Files.delete(directory);
        }
    }

    private static void publicationJournalPreservesEvidence() throws Exception {
        var directory = java.nio.file.Files.createTempDirectory("hosthop-journal-test-");
        var path = directory.resolve("pending");
        try {
            var expected = publication(5, 3, "operation", "digest");
            PendingPublicationJournal.create(path, expected);
            check(expected.equals(PendingPublicationJournal.read(path)), "journal did not round-trip");
            try {
                PendingPublicationJournal.create(path, publication(5, 3, "other", "digest"));
                throw new AssertionError("journal was overwritten");
            } catch (java.nio.file.FileAlreadyExistsException expectedFailure) { }
            check(expected.equals(PendingPublicationJournal.read(path)), "overwrite changed journal");
            var bytes = java.nio.file.Files.readAllBytes(path);
            bytes[10] ^= 1;
            java.nio.file.Files.write(path, bytes);
            expectJournalFailure(path);
            java.nio.file.Files.write(path, new byte[] { 1, 2, 3 });
            expectJournalFailure(path);
            java.nio.file.Files.delete(path);
            expectJournalFailure(path);
        } finally {
            java.nio.file.Files.deleteIfExists(path);
            java.nio.file.Files.delete(directory);
        }
    }

    private static void expectJournalFailure(java.nio.file.Path path) throws Exception {
        try {
            PendingPublicationJournal.read(path);
            throw new AssertionError("invalid journal accepted");
        } catch (java.io.IOException expected) { }
    }

    private static void gracefulPublicationRequiresOrderedEvidence() {
        var gate = new LocalHostGate();
        var attempt = gate.beginAcquire();
        check(gate.acceptGrant(attempt, 4, 7, deadline(), 0, 0), "grant rejected");
        check(gate.snapshotVerified(attempt, 7, 0, 0), "snapshot rejected");
        check(gate.beginHosting(attempt, 0, 0), "host start failed");
        check(!gate.finalSnapshotVerified(attempt, 7, 0, 0), "snapshot accepted while hosting");
        check(gate.beginGracefulStop(attempt, 0, 0), "graceful stop rejected");
        check(!gate.mayContinueHosting(attempt, 0, 0), "hosting allowed during shutdown");
        check(!gate.finalSnapshotVerified(attempt, 7, 0, 0), "snapshot accepted before stop");
        check(gate.serverStoppedForSnapshot(attempt, 0, 0), "server stop rejected");
        check(!gate.finalSnapshotVerified(attempt, 6, 0, 0), "wrong base generation accepted");
        var renewal = gate.beginRenewal(attempt, 5_000_000_000L, 5000);
        var extension = ConservativeLeaseDeadline.fromGrant(5_000_000_000L, 5000, 105, 135, 2);
        check(gate.acceptRenewal(attempt, renewal, 4, extension, 6_000_000_000L, 6000), "snapshot renewal rejected");
        check(gate.finalSnapshotVerified(attempt, 7, 6_000_000_000L, 6000), "verified snapshot rejected");
        var operation = publication(4, 7, "op", "digest");
        check(!gate.mayPublish(attempt, publication(3, 7, "op", "digest"), 6_000_000_000L, 6000), "wrong epoch accepted");
        check(gate.mayPublish(attempt, operation, 6_000_000_000L, 6000), "publication rejected");
        check(!gate.mayPublish(attempt, publication(4, 7, "other", "digest"), 6_000_000_000L, 6000), "operation changed during retry");
        check(!gate.publicationConfirmed(attempt, publication(4, 7, "op", "wrong"), 6_000_000_000L, 6000), "incorrect digest accepted");
        check(gate.publicationConfirmed(attempt, operation, 6_000_000_000L, 6000), "commit confirmation rejected");
        expectAcquireFailure(gate);
        check(!gate.requireStop(attempt), "stop request bypassed release");
        check(!gate.confirmStopped(attempt), "stop confirmation bypassed release");
        check(!gate.confirmReleased(attempt, new ReleaseRecord("world", "owner", "lease", 4, "other", 8)), "wrong release accepted");
        var release = new ReleaseRecord("world", "owner", "lease", 4, "op", 8);
        check(gate.confirmReleased(attempt, release), "release rejected");
        var next = gate.beginAcquire();
        check(!gate.confirmReleased(attempt, release), "stale release accepted");
        check(gate.requireStop(next), "new attempt unavailable");
    }

    private static void expiredPublicationRequiresReconciliation() {
        var gate = new LocalHostGate();
        var attempt = gate.beginAcquire();
        check(gate.acceptGrant(attempt, 5, 3, deadline(), 0, 0), "grant rejected");
        check(gate.snapshotVerified(attempt, 3, 0, 0), "snapshot rejected");
        check(gate.beginHosting(attempt, 0, 0), "host start failed");
        check(gate.beginGracefulStop(attempt, 0, 0), "shutdown rejected");
        check(gate.serverStoppedForSnapshot(attempt, 0, 0), "stop rejected");
        check(gate.finalSnapshotVerified(attempt, 3, 0, 0), "snapshot rejected");
        var operation = publication(5, 3, "op", "digest");
        check(!gate.publicationConfirmed(attempt, operation, 0, 0), "unsent publication confirmed");
        check(gate.mayPublish(attempt, operation, 0, 0), "publication rejected");
        check(!gate.publicationConfirmed(attempt, operation, 27_000_000_000L, 27_000), "late acknowledgement accepted");
        check(gate.phase() == LocalHostGate.Phase.STOP_REQUIRED, "expiry did not require stop");
        check(!gate.mayPublish(attempt, operation, 27_000_000_000L, 27_000), "expired publication allowed");
        expectAcquireFailure(gate);
        check(!gate.confirmStopped(attempt), "uncertain publication bypassed reconciliation");
        check(!gate.publicationReconciled(attempt, null), "missing receipt accepted");
        check(!gate.publicationReconciled(attempt, publication(5, 3, "other", "digest")), "wrong operation reconciled");
        check(!gate.publicationReconciled(attempt, publication(5, 3, "op", "wrong")), "wrong digest reconciled");
        check(gate.publicationReconciled(attempt, operation), "reconciliation rejected");
        check(gate.confirmStopped(attempt), "reconciled stop rejected");
    }

    private static ConservativeLeaseDeadline deadline() {
        return ConservativeLeaseDeadline.fromGrant(0, 0, 100, 130, 2);
    }

    private static PublicationRecord publication(long epoch, long generation, String operation, String digest) {
        return new PublicationRecord("world", operation, "owner", "lease", epoch, generation, digest, generation + 1);
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
