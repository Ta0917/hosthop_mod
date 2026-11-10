ALTER TABLE world_checkpoints ADD COLUMN managed INTEGER NOT NULL DEFAULT 0 CHECK (managed IN (0, 1));
ALTER TABLE world_checkpoints ADD COLUMN base_object_id TEXT NOT NULL DEFAULT '';
ALTER TABLE checkpoint_deletions ADD COLUMN kind TEXT NOT NULL DEFAULT 'delta';
CREATE INDEX IF NOT EXISTS checkpoint_base_object ON world_checkpoints(world_id, base_object_id);
CREATE VIEW IF NOT EXISTS prunable_full_checkpoints AS
SELECT c.world_id, c.checkpoint_id FROM world_checkpoints c
WHERE c.kind = 'full' AND c.managed = 1
  AND c.checkpoint_id NOT IN (
    SELECT newest.checkpoint_id FROM world_checkpoints newest
    WHERE newest.world_id = c.world_id AND newest.kind = 'full'
    ORDER BY newest.created_at DESC, newest.rowid DESC LIMIT 4
  )
  AND NOT EXISTS (SELECT 1 FROM worlds w WHERE w.id = c.world_id AND (w.object_id = c.object_id OR w.lease_id = c.lease_id))
  AND NOT EXISTS (SELECT 1 FROM committed_operations op WHERE op.world_id = c.world_id AND op.object_id = c.object_id)
  AND NOT EXISTS (SELECT 1 FROM world_checkpoints d WHERE d.world_id = c.world_id AND d.base_object_id = c.object_id)
  AND NOT EXISTS (SELECT 1 FROM world_checkpoints d WHERE d.world_id = c.world_id AND d.kind = 'delta'
      AND d.managed = 0 AND d.lease_id = c.lease_id);
DROP TRIGGER IF EXISTS apply_checkpoint_deletion;
CREATE TRIGGER apply_checkpoint_deletion AFTER INSERT ON checkpoint_deletions
BEGIN
    DELETE FROM world_checkpoints WHERE world_id = NEW.world_id AND checkpoint_id = NEW.checkpoint_id
      AND object_id = NEW.object_id AND digest = NEW.digest AND object_size = NEW.object_size AND kind = NEW.kind
      AND (kind = 'delta' OR EXISTS (SELECT 1 FROM prunable_full_checkpoints p WHERE p.world_id = NEW.world_id AND p.checkpoint_id = NEW.checkpoint_id))
      AND NOT EXISTS (SELECT 1 FROM worlds w WHERE w.id = NEW.world_id AND w.object_id = NEW.object_id)
      AND NOT EXISTS (SELECT 1 FROM committed_operations op WHERE op.world_id = NEW.world_id AND op.object_id = NEW.object_id);
    SELECT CASE WHEN changes() != 1 THEN RAISE(ABORT, 'unsafe checkpoint deletion') END;
END;
