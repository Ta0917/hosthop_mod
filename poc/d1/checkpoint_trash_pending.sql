SELECT checkpoint_id, generation, digest, object_id, object_size, kind, created_at
FROM checkpoint_deletions WHERE world_id = ?1 AND owner = ?2 AND cleaned = 0 AND (kind = 'delta' OR ?3 = 1)
ORDER BY created_at DESC, rowid DESC LIMIT 2;
