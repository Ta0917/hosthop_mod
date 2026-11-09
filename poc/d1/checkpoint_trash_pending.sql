SELECT checkpoint_id, generation, digest, object_id, object_size, 'delta' AS kind, created_at
FROM checkpoint_deletions WHERE world_id = ?1 AND owner = ?2 AND cleaned = 0 ORDER BY created_at DESC LIMIT 2;
