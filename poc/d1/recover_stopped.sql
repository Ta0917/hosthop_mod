-- Recovery is authorized only for the same expired lease and unchanged generation.
-- The caller must first verify that the host and relay have stopped and validate
-- the uploaded object. The trigger commits and releases in one transaction.
INSERT INTO stopped_recoveries (world_id, operation_id, owner, lease_id, epoch, previous_generation, digest, object_id, object_size)
SELECT id, ?2, owner, lease_id, epoch, generation, ?7, ?8, ?9
FROM worlds
WHERE id = ?1 AND owner = ?3 AND lease_id = ?4 AND epoch = ?5
  AND generation = ?6 AND expires <= unixepoch('now')
  AND typeof(?2) = 'text' AND length(?2) > 0
  AND typeof(?7) = 'text' AND length(?7) > 0
  AND typeof(?8) = 'text' AND length(?8) > 0
  AND typeof(?9) IN ('integer', 'real') AND ?9 = CAST(?9 AS INTEGER) AND ?9 BETWEEN 1 AND 68719476736
ON CONFLICT (world_id, operation_id) DO NOTHING
RETURNING operation_id;
