SELECT world_id, checkpoint_id, object_id, digest, object_size FROM checkpoint_deletions WHERE world_id = ?1 AND checkpoint_id = ?2;
