-- Bind world, owner, lease, epoch, final commit operation, final generation.
-- The caller must authenticate the request and verify its exact retry record.
INSERT INTO lease_releases (world_id, lease_id, owner, epoch, operation_id, result_generation)
SELECT w.id, w.lease_id, w.owner, w.epoch, c.operation_id, c.result_generation
FROM worlds w JOIN committed_operations c ON c.world_id = w.id
WHERE w.id = ?1 AND w.owner = ?2 AND w.lease_id = ?3 AND w.epoch = ?4
  AND c.operation_id = ?5 AND c.owner = w.owner AND c.lease_id = w.lease_id
  AND c.epoch = w.epoch AND c.result_generation = ?6 AND w.generation = ?6
ON CONFLICT (world_id, lease_id) DO NOTHING
RETURNING result_generation;
