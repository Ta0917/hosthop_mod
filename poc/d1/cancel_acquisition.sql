INSERT INTO lease_cancellations (world_id, lease_id, owner)
SELECT w.id, ?3, ?2 FROM worlds w
WHERE w.id = ?1 AND NOT EXISTS (
  SELECT 1 FROM lease_grants g WHERE g.world_id = ?1 AND g.lease_id = ?3
)
ON CONFLICT (world_id, lease_id) DO NOTHING;
