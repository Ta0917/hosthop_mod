SELECT world_id, checkpoint_id, owner, lease_id, epoch, generation, digest, object_id, object_size, kind, created_at, managed, base_object_id
FROM world_checkpoints WHERE world_id = ?1 AND checkpoint_id = ?2;
