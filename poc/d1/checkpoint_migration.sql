CREATE TABLE IF NOT EXISTS world_checkpoints (
    world_id TEXT NOT NULL, checkpoint_id TEXT NOT NULL, owner TEXT NOT NULL,
    lease_id TEXT NOT NULL, epoch INTEGER NOT NULL, generation INTEGER NOT NULL,
    digest TEXT NOT NULL, object_id TEXT NOT NULL, object_size INTEGER NOT NULL,
    kind TEXT NOT NULL, created_at INTEGER NOT NULL,
    PRIMARY KEY (world_id, checkpoint_id)
);
CREATE TABLE IF NOT EXISTS checkpoint_deletions (
    world_id TEXT NOT NULL, checkpoint_id TEXT NOT NULL, object_id TEXT NOT NULL, digest TEXT NOT NULL,
    object_size INTEGER NOT NULL, owner TEXT NOT NULL, generation INTEGER NOT NULL, created_at INTEGER NOT NULL,
    cleaned INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (world_id, checkpoint_id)
);
CREATE TRIGGER IF NOT EXISTS apply_checkpoint_deletion AFTER INSERT ON checkpoint_deletions
BEGIN
    DELETE FROM world_checkpoints WHERE world_id = NEW.world_id AND checkpoint_id = NEW.checkpoint_id
      AND object_id = NEW.object_id AND digest = NEW.digest AND object_size = NEW.object_size AND kind = 'delta'
      AND NOT EXISTS (SELECT 1 FROM worlds w WHERE w.id = NEW.world_id AND w.object_id = NEW.object_id)
      AND NOT EXISTS (SELECT 1 FROM committed_operations c WHERE c.world_id = NEW.world_id AND c.object_id = NEW.object_id);
    SELECT CASE WHEN changes() != 1 THEN RAISE(ABORT, 'unsafe checkpoint deletion') END;
END;
CREATE TABLE IF NOT EXISTS backup_settings (
    world_id TEXT PRIMARY KEY, keep_delta INTEGER NOT NULL CHECK (keep_delta BETWEEN 1 AND 28)
);
CREATE UNIQUE INDEX IF NOT EXISTS checkpoint_unique_object ON world_checkpoints(world_id, object_id);
