INSERT INTO checkpoint_deletions (world_id, checkpoint_id, object_id, digest, object_size, owner, generation, created_at)
SELECT c.world_id, c.checkpoint_id, c.object_id, c.digest, c.object_size, c.owner, c.generation, c.created_at FROM world_checkpoints c
WHERE c.world_id = ?1 AND c.checkpoint_id = ?2 AND c.object_id = ?3 AND c.digest = ?4 AND c.object_size = ?5 AND c.owner = ?7
  AND c.kind = 'delta' AND (SELECT COALESCE((SELECT keep_delta FROM backup_settings WHERE world_id = ?1), 4)) = ?6
  AND c.checkpoint_id IN (SELECT checkpoint_id FROM world_checkpoints WHERE world_id = ?1 AND kind = 'delta'
      ORDER BY created_at DESC, rowid DESC LIMIT -1 OFFSET ?6)
  AND NOT EXISTS (SELECT 1 FROM worlds w WHERE w.id = c.world_id AND w.object_id = c.object_id)
  AND NOT EXISTS (SELECT 1 FROM committed_operations x WHERE x.world_id = c.world_id AND x.object_id = c.object_id)
ON CONFLICT (world_id, checkpoint_id) DO NOTHING;
