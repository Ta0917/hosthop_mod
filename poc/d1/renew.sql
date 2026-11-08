-- Experimental: bind ?1 world_id, ?2 owner, ?3 lease_id, ?4 epoch, ?5 duration_seconds.
UPDATE worlds
SET expires = max(expires, unixepoch('now') + ?5)
WHERE id = ?1 AND owner = ?2 AND lease_id = ?3 AND epoch = ?4
  AND expires > unixepoch('now') AND typeof(?5) IN ('integer', 'real')
  AND ?5 = CAST(?5 AS INTEGER) AND ?5 BETWEEN 1 AND 300
RETURNING epoch, generation, expires, unixepoch('now') AS server_now;
