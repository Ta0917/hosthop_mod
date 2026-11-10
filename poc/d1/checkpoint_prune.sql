SELECT c.checkpoint_id, c.generation, c.digest, c.object_id, c.object_size, c.kind, c.created_at
FROM world_checkpoints c WHERE c.world_id = ?1 AND c.owner = ?2
  AND (c.kind = 'delta' AND c.checkpoint_id IN (SELECT checkpoint_id FROM world_checkpoints WHERE world_id = ?1 AND kind = 'delta'
    ORDER BY created_at DESC, rowid DESC LIMIT -1 OFFSET (SELECT COALESCE((SELECT keep_delta FROM backup_settings WHERE world_id = ?1), 4)))
    OR ?3 = 1 AND EXISTS (SELECT 1 FROM prunable_full_checkpoints p WHERE p.world_id = c.world_id AND p.checkpoint_id = c.checkpoint_id))
  AND NOT EXISTS (SELECT 1 FROM worlds w WHERE w.id = c.world_id AND w.object_id = c.object_id)
  AND NOT EXISTS (SELECT 1 FROM committed_operations op WHERE op.world_id = c.world_id AND op.object_id = c.object_id)
ORDER BY created_at DESC, rowid DESC LIMIT 2;
