package dev.hosthop.world;

import dev.hosthop.lease.LeaseAuthorityClient;
import dev.hosthop.lease.LeaseCoordinator;
import dev.hosthop.lease.LocalHostGate;
import dev.hosthop.lease.PendingPublicationJournal;
import dev.hosthop.lease.PublicationPreparation;
import dev.hosthop.lease.PublicationRecord;
import dev.hosthop.snapshot.SnapshotVerifier;
import dev.hosthop.snapshot.SnapshotWriter;
import dev.hosthop.snapshot.StoppedWorldLock;
import dev.hosthop.storage.SnapshotStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;

/** Shared-world transfer lifecycle. The caller owns Minecraft and relay start/stop. */
public final class SharedWorldTransfer {
    private final LocalHostGate gate;
    private final LeaseAuthorityClient authority;
    private final SnapshotStore storage;
    private final LongSupplier nanos, millis;

    public SharedWorldTransfer(LocalHostGate gate, LeaseAuthorityClient authority, SnapshotStore storage,
                               LongSupplier nanos, LongSupplier millis) {
        if (gate == null || authority == null || storage == null || nanos == null || millis == null)
            throw new IllegalArgumentException("Missing shared-world input");
        this.gate = gate; this.authority = authority; this.storage = storage; this.nanos = nanos; this.millis = millis;
    }

    /** Restores to a new world directory; generation zero imports a stopped local copy. */
    public void prepare(LeaseCoordinator.Acquisition acquisition, Path initialCopy, Path archive, Path destination,
                        String driveToken) throws IOException, InterruptedException {
        if (acquisition == null || acquisition.epoch() < 1 || acquisition.generation() < 0) throw new IllegalStateException("Lease not acquired");
        if (acquisition.generation() == 0) {
            if (initialCopy == null) throw new IOException("Initial world copy required");
            try (var lock = StoppedWorldLock.acquire(initialCopy)) { SnapshotWriter.create(initialCopy, archive); }
        } else {
            var snapshot = authority.snapshot(acquisition.world());
            if (snapshot.generation() != acquisition.generation()) throw new IOException("Snapshot generation changed");
            storage.download(snapshot.object(), archive, driveToken);
        }
        SnapshotVerifier.restore(archive, destination);
        if (!gate.snapshotVerified(acquisition.attempt(), acquisition.generation(), nanos.getAsLong(), millis.getAsLong()))
            throw new IOException("Lease expired while preparing world");
    }

    /**
     * Call only after the integrated server and external relay have fully stopped.
     * Lease renewal must continue separately while this blocking operation runs.
     */
    public PublicationRecord publishAfterStopped(LeaseCoordinator.Acquisition acquisition, Path stoppedWorld, Path archive,
                                                 Path journal, String operationId, String folderId, String driveToken)
            throws IOException, InterruptedException {
        return publishAfterStopped(acquisition, stoppedWorld, archive, journal, operationId, folderId, driveToken, false);
    }

    /** Retain the receipt journal until the caller clears its separate host journal. */
    public PublicationRecord publishAfterStopped(LeaseCoordinator.Acquisition acquisition, Path stoppedWorld, Path archive,
                                                 Path journal, String operationId, String folderId, String driveToken, boolean retainJournal)
            throws IOException, InterruptedException {
        if (acquisition == null || acquisition.epoch() < 1 || acquisition.generation() < 0) throw new IllegalStateException("Lease not acquired");
        if (acquisition.generation() == Long.MAX_VALUE || operationId == null || !operationId.matches("[A-Za-z0-9_-]{1,80}"))
            throw new IllegalArgumentException("Invalid publication operation");
        if (!gate.serverStoppedForSnapshot(acquisition.attempt(), nanos.getAsLong(), millis.getAsLong()))
            throw new IOException("Server stop was not accepted");
        try (var lock = StoppedWorldLock.acquire(stoppedWorld)) { SnapshotWriter.create(stoppedWorld, archive); }
        var object = storage.upload(archive, folderId, driveToken);
        if (!gate.finalSnapshotVerified(acquisition.attempt(), acquisition.generation(), nanos.getAsLong(), millis.getAsLong()))
            throw new IOException("Lease expired before publication");
        var operation = new PublicationRecord(acquisition.world(), operationId, acquisition.principal(), acquisition.lease(),
                acquisition.epoch(), acquisition.generation(), object.sha256(), object.fileId(), object.size(), acquisition.generation() + 1);
        if (!PublicationPreparation.prepare(gate, acquisition.attempt(), journal, operation, nanos, millis))
            throw new IOException("Lease expired before publication");
        try {
            var receipt = authority.commit(operation, driveToken);
            if (!gate.publicationConfirmed(acquisition.attempt(), receipt, nanos.getAsLong(), millis.getAsLong()))
                throw new IOException("Publication needs reconciliation");
        } catch (IOException | InterruptedException | RuntimeException failure) {
            gate.requireStop(acquisition.attempt());
            throw failure;
        }
        completeRelease(acquisition.attempt(), journal, operation, retainJournal);
        return operation;
    }

    /** Safe to retry after a lost release response. */
    public void completeRelease(LocalHostGate.Attempt attempt, Path journal, PublicationRecord operation) throws IOException, InterruptedException {
        completeRelease(attempt, journal, operation, false);
    }

    public void completeRelease(LocalHostGate.Attempt attempt, Path journal, PublicationRecord operation, boolean retainJournal) throws IOException, InterruptedException {
        if (!gate.awaitingRelease(attempt, operation) || !PendingPublicationJournal.read(journal).equals(operation)) throw new IOException("Publication journal mismatch");
        var receipt = authority.release(operation);
        if (!receipt.matches(operation)) throw new IOException("Release receipt mismatch");
        synchronized (gate) {
            if (!gate.awaitingRelease(attempt, operation) || !PendingPublicationJournal.read(journal).equals(operation)) throw new IOException("Release state changed");
            if (!retainJournal) Files.delete(journal);
            if (!gate.confirmReleased(attempt, receipt)) throw new IOException("Release state changed");
        }
    }

    /** Call only after the server and relay are stopped, with the matching persisted journal. */
    public void recoverStopped(LocalHostGate.Attempt attempt, Path journal) throws IOException, InterruptedException {
        recoverStopped(attempt, journal, false);
    }

    public void recoverStopped(LocalHostGate.Attempt attempt, Path journal, boolean retainJournal) throws IOException, InterruptedException {
        recoverStopped(attempt, journal, retainJournal, null);
    }

    /** An unconfirmed operation may be safely retried only with its exact persisted identity. */
    public void recoverStopped(LocalHostGate.Attempt attempt, Path journal, boolean retainJournal, String driveToken) throws IOException, InterruptedException {
        var operation = PendingPublicationJournal.read(journal);
        if (!gate.awaitingReconciliation(attempt, operation)) throw new IOException("Publication recovery state mismatch");
        PublicationRecord receipt;
        try { receipt = authority.commitStatus(operation); }
        catch (LeaseAuthorityClient.PublicationNotFound missing) {
            if (driveToken == null) throw missing;
            receipt = authority.commit(operation, driveToken);
        }
        authority.release(operation);
        synchronized (gate) {
            if (!gate.awaitingReconciliation(attempt, operation) || !PendingPublicationJournal.read(journal).equals(operation)) throw new IOException("Publication recovery state changed");
            if (!gate.publicationReconciled(attempt, receipt)) throw new IOException("Publication reconciliation rejected");
            if (!retainJournal) Files.delete(journal);
            if (!gate.confirmStopped(attempt)) throw new IOException("Stop confirmation rejected");
        }
    }
}
