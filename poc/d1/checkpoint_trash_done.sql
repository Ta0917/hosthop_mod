UPDATE checkpoint_deletions SET cleaned = 1 WHERE world_id = ?1 AND checkpoint_id = ?2
  AND object_id = ?3 AND digest = ?4 AND object_size = ?5 RETURNING world_id, checkpoint_id, object_id, digest, object_size;
