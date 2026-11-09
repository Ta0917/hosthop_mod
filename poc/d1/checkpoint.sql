INSERT INTO world_checkpoints (world_id, checkpoint_id, owner, lease_id, epoch, generation, digest, object_id, object_size, kind, created_at)
SELECT id, ?2, owner, lease_id, epoch, generation, ?7, ?8, ?9, ?10, unixepoch('now')
FROM worlds WHERE id = ?1 AND owner = ?3 AND lease_id = ?4 AND epoch = ?5 AND generation = ?6 AND expires > unixepoch('now')
  AND typeof(?2) = 'text' AND length(?2) BETWEEN 1 AND 80
  AND typeof(?7) = 'text' AND length(?7) = 64 AND typeof(?8) = 'text' AND length(?8) BETWEEN 1 AND 200
  AND typeof(?9) IN ('integer', 'real') AND ?9 = CAST(?9 AS INTEGER) AND ?9 BETWEEN 1 AND 68719476736
  AND ?10 IN ('full', 'delta')
  AND NOT EXISTS (SELECT 1 FROM world_checkpoints c WHERE c.world_id = ?1 AND c.object_id = ?8 AND c.checkpoint_id <> ?2)
  AND NOT EXISTS (SELECT 1 FROM checkpoint_deletions d WHERE d.world_id = ?1 AND d.object_id = ?8)
ON CONFLICT (world_id, checkpoint_id) DO NOTHING
RETURNING checkpoint_id;
