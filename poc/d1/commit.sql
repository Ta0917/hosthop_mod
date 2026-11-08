-- Bind world, operation, owner, lease, epoch, previous generation, digest, object ID, byte size.
-- SQLite time is used in the statement. Clock guarantees in deployed D1 are unverified.
INSERT INTO committed_operations (world_id, operation_id, owner, lease_id, epoch, previous_generation, digest, object_id, object_size, result_generation)
SELECT id, ?2, owner, lease_id, epoch, generation, ?7, ?8, ?9, generation + 1
FROM worlds
WHERE id = ?1 AND owner = ?3 AND lease_id = ?4 AND epoch = ?5
  AND generation = ?6 AND expires > unixepoch('now')
  AND typeof(?2) = 'text' AND length(?2) > 0
  AND typeof(?7) = 'text' AND length(?7) > 0
  AND typeof(?8) = 'text' AND length(?8) > 0
  AND typeof(?9) IN ('integer', 'real') AND ?9 = CAST(?9 AS INTEGER) AND ?9 BETWEEN 1 AND 68719476736
ON CONFLICT (world_id, operation_id) DO NOTHING
RETURNING result_generation;
