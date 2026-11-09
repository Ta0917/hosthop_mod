package dev.hosthop.lease;

import dev.hosthop.storage.GoogleDriveSnapshotStore.ObjectRef;

/** Identity of a point-in-time backup made by a live lease holder. */
public record CheckpointRecord(String world, String id, String owner, String lease, long epoch,
                               long generation, ObjectRef object, String kind, long createdAt) {
    public CheckpointRecord {
        if (world == null || !world.matches("[A-Za-z0-9_-]{1,80}") || id == null || !id.matches("[A-Za-z0-9_-]{1,80}")
                || owner == null || !owner.matches("[A-Za-z0-9_-]{1,80}") || lease == null || !lease.matches("[A-Za-z0-9_-]{1,80}")
                || epoch < 1 || generation < 0 || object == null || object.fileId() == null || !object.fileId().matches("[A-Za-z0-9_-]{1,200}")
                || object.sha256() == null || !object.sha256().matches("[0-9a-f]{64}") || object.size() < 1 || object.size() > 68_719_476_736L
                || !("full".equals(kind) || "delta".equals(kind)) || createdAt < 0) throw new IllegalArgumentException("Invalid checkpoint");
    }
}
