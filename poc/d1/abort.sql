-- Bind world, owner, lease, epoch, base generation. The client must ensure it never started a server.
INSERT INTO lease_aborts (world_id, lease_id, owner, epoch, base_generation)
SELECT w.id, w.lease_id, w.owner, w.epoch, g.base_generation
FROM worlds w JOIN lease_grants g ON g.world_id = w.id AND g.lease_id = w.lease_id
WHERE w.id = ?1 AND w.owner = ?2 AND w.lease_id = ?3 AND w.epoch = ?4
  AND g.owner = w.owner AND g.epoch = w.epoch AND g.base_generation = ?5
  AND w.generation = ?5 AND w.endpoint IS NULL
ON CONFLICT (world_id, lease_id) DO NOTHING
RETURNING epoch;
