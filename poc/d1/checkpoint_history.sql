SELECT checkpoint_id, generation, digest, object_id, object_size, kind, created_at
FROM world_checkpoints
WHERE world_id = ?1 AND (
  checkpoint_id IN (SELECT checkpoint_id FROM world_checkpoints WHERE world_id = ?1 ORDER BY created_at DESC, rowid DESC LIMIT 28)
  OR checkpoint_id IN (SELECT checkpoint_id FROM world_checkpoints WHERE world_id = ?1 AND kind = 'full' ORDER BY created_at DESC, rowid DESC LIMIT 4)
)
ORDER BY created_at DESC, rowid DESC LIMIT 32;
