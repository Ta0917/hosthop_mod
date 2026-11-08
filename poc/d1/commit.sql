-- Bind ?1 world_id, ?2 operation_id, ?3 owner, ?4 lease_id, ?5 epoch, ?6 previous_generation, ?7 digest.
-- SQLite time is used in the statement. Clock guarantees in deployed D1 are unverified.
INSERT INTO committed_operations (world_id, operation_id, owner, lease_id, epoch, previous_generation, digest, result_generation)
SELECT id, ?2, owner, lease_id, epoch, generation, ?7, generation + 1
FROM worlds
WHERE id = ?1 AND owner = ?3 AND lease_id = ?4 AND epoch = ?5
  AND generation = ?6 AND expires > unixepoch('now')
  AND typeof(?2) = 'text' AND length(?2) > 0
  AND typeof(?7) = 'text' AND length(?7) > 0
ON CONFLICT (world_id, operation_id) DO NOTHING
RETURNING result_generation;
