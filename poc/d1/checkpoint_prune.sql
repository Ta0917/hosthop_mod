SELECT checkpoint_id, generation, digest, object_id, object_size, kind, created_at
FROM world_checkpoints WHERE world_id = ?1 AND kind = 'delta' AND owner = ?2
  AND checkpoint_id IN (SELECT checkpoint_id FROM world_checkpoints WHERE world_id = ?1 AND kind = 'delta'
    ORDER BY created_at DESC, rowid DESC LIMIT -1 OFFSET (SELECT COALESCE((SELECT keep_delta FROM backup_settings WHERE world_id = ?1), 4)))
ORDER BY created_at DESC, rowid DESC LIMIT 2;
