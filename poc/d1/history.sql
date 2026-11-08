-- Four latest committed generations; historical objects are never deleted here.
SELECT result_generation AS generation, digest, object_id, object_size
FROM committed_operations WHERE world_id = ?1 ORDER BY result_generation DESC LIMIT 4;
