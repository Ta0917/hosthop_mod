-- Bind world ID; membership authentication must be enforced by the caller.
-- base_object_id is empty for a full snapshot or when the dependency was never recorded.
SELECT w.generation, w.digest, w.object_id, w.object_size,
  COALESCE((SELECT b.base_object_id FROM snapshot_bases b WHERE b.world_id = w.id AND b.object_id = w.object_id),
           (SELECT c.base_object_id FROM world_checkpoints c WHERE c.world_id = w.id AND c.object_id = w.object_id AND c.base_object_id <> '' LIMIT 1),
           '') AS base_object_id
FROM worlds w WHERE w.id = ?1;
