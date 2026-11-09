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
import dev.hosthop.snapshot.LocalSnapshotCache;
import dev.hosthop.snapshot.BinaryDelta;
import dev.hosthop.storage.SnapshotStore;
import dev.hosthop.storage.GoogleDriveSnapshotStore;
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
    private final java.util.function.LongConsumer uploadProgress;
    private GoogleDriveSnapshotStore.ObjectRef baseRef;
    private Path baseArchive;

    public SharedWorldTransfer(LocalHostGate gate, LeaseAuthorityClient authority, SnapshotStore storage,
                               LongSupplier nanos, LongSupplier millis) {
        this(gate, authority, storage, nanos, millis, ignored -> {});
    }

    public SharedWorldTransfer(LocalHostGate gate, LeaseAuthorityClient authority, SnapshotStore storage,
                               LongSupplier nanos, LongSupplier millis, java.util.function.LongConsumer uploadProgress) {
        if (gate == null || authority == null || storage == null || nanos == null || millis == null)
            throw new IllegalArgumentException("Missing shared-world input");
        if (uploadProgress == null) throw new IllegalArgumentException("Missing progress callback");
        this.gate = gate; this.authority = authority; this.storage = storage; this.nanos = nanos; this.millis = millis;
        this.uploadProgress = uploadProgress;
    }

    /** Restores to a new world directory; generation zero imports a stopped local copy. */
    public void prepare(LeaseCoordinator.Acquisition acquisition, Path initialCopy, Path archive, Path destination,
                        String driveToken) throws IOException, InterruptedException {
        prepare(acquisition, initialCopy, archive, destination, driveToken, null);
    }

    public void prepare(LeaseCoordinator.Acquisition acquisition, Path initialCopy, Path archive, Path destination,
                        String driveToken, Path cacheSessions) throws IOException, InterruptedException {
        if (acquisition == null || acquisition.epoch() < 1 || acquisition.generation() < 0) throw new IllegalStateException("Lease not acquired");
        if (acquisition.generation() == 0) {
            if (initialCopy == null) throw new IOException("Initial world copy required");
            try (var lock = StoppedWorldLock.acquire(initialCopy)) { SnapshotWriter.create(initialCopy, archive); }
        } else {
            var snapshot = authority.snapshot(acquisition.world());
            if (snapshot.generation() != acquisition.generation()) throw new IOException("Snapshot generation changed");
            Path stored = archive.resolveSibling("remote-object.bin");
            boolean cached = false;
            try { if (cacheSessions != null) cached = LocalSnapshotCache.copyMatching(cacheSessions, stored, snapshot.object()); }
            catch (IOException cacheError) {
                if (Files.exists(stored)) throw cacheError;
                cached = false;
            }
            if (!cached)
                storage.downloadRaw(snapshot.object(), stored, driveToken);
            if (BinaryDelta.isDelta(stored)) {
                var delta = BinaryDelta.inspect(stored);
                Path base = archive.resolveSibling("base.zip");
                boolean baseCached = false;
                try { if (cacheSessions != null) baseCached = LocalSnapshotCache.copyMatching(cacheSessions, base, delta.base()); }
                catch (IOException cacheError) {
                    if (Files.exists(base)) throw cacheError;
                }
                if (!baseCached) storage.download(delta.base(), base, driveToken);
                else SnapshotVerifier.verify(base);
                BinaryDelta.apply(stored, base, archive);
                baseRef = delta.base(); baseArchive = base;
            } else {
                SnapshotVerifier.verify(stored);
                Files.move(stored, archive);
                baseRef = snapshot.object(); baseArchive = archive;
            }
        }
        storage.checkUploadCapacity(Files.size(archive), driveToken);
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
        Path upload = archive;
        if (baseRef != null && baseArchive != null && storage.canUseBase(baseRef, folderId, driveToken)) {
            Path delta = archive.resolveSibling("delta.bin");
            Path verified = archive.resolveSibling("delta-verified.zip");
            if (!Files.exists(delta, java.nio.file.LinkOption.NOFOLLOW_LINKS) && !Files.exists(verified, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                boolean deltaCreated = false, verifiedCreated = false;
                try {
                    BinaryDelta.create(baseArchive, baseRef, archive, delta);
                    deltaCreated = true;
                    if (Files.size(delta) < Files.size(archive) * 3 / 4) {
                        BinaryDelta.apply(delta, baseArchive, verified);
                        verifiedCreated = true;
                        if (Files.mismatch(archive, verified) != -1) throw new IOException("Delta verification mismatch");
                        upload = delta;
                    } else Files.delete(delta);
                } catch (IOException deltaError) {
                    com.mojang.logging.LogUtils.getLogger().warn("HostHop delta unavailable; using full snapshot ({})", deltaError.getClass().getSimpleName());
                    if (deltaCreated) Files.deleteIfExists(delta);
                } finally { if (verifiedCreated) Files.deleteIfExists(verified); }
            }
        }
        GoogleDriveSnapshotStore.ObjectRef object;
        try { object = storage.upload(upload, folderId, driveToken, uploadProgress); }
        catch (IOException | InterruptedException failure) {
            com.mojang.logging.LogUtils.getLogger().warn("HostHop snapshot upload failed ({})", failure.getClass().getSimpleName());
            throw failure;
        }
        if (!upload.equals(archive) && !storage.canUseBase(baseRef, folderId, driveToken))
            throw new IOException("Delta base is unavailable before commit");
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
        recoverStopped(attempt, journal, retainJournal, driveToken, null);
    }

    public void recoverStopped(LocalHostGate.Attempt attempt, Path journal, boolean retainJournal, String driveToken, String folderId) throws IOException, InterruptedException {
        var operation = PendingPublicationJournal.read(journal);
        if (!gate.awaitingReconciliation(attempt, operation)) throw new IOException("Publication recovery state mismatch");
        PublicationRecord receipt;
        try { receipt = authority.commitStatus(operation); }
        catch (LeaseAuthorityClient.PublicationNotFound missing) {
            if (driveToken == null) throw missing;
            // A previously uploaded delta is useful only while its immutable base
            // remains available. Check again before retrying an uncertain commit.
            Path downloaded = journal.resolveSibling("recovery-object-" + java.util.UUID.randomUUID() + ".bin");
            boolean downloadedHere = false;
            try {
                storage.downloadRaw(new GoogleDriveSnapshotStore.ObjectRef(operation.objectId(), operation.digest(), operation.objectSize()), downloaded, driveToken);
                downloadedHere = true;
                if (BinaryDelta.isDelta(downloaded)) {
                    var base = BinaryDelta.inspect(downloaded).base();
                    if (folderId == null || !storage.canUseBase(base, folderId, driveToken)) throw new IOException("Delta base is unavailable during recovery");
                } else SnapshotVerifier.verify(downloaded);
            } finally { if (downloadedHere) Files.deleteIfExists(downloaded); }
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
