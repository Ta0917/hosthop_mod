-- Experimental: bind ?1 world_id, ?2 owner, ?3 lease_id, ?4 duration_seconds.
-- No user authentication or network fencing. A retry must inspect its stored
-- grant and the current world row on the primary before treating it as active.
INSERT INTO lease_grants (world_id, lease_id, owner, epoch, base_generation, initial_expires, duration_seconds)
SELECT id, ?3, ?2, epoch + 1, generation, unixepoch('now') + ?4, ?4
FROM worlds WHERE id = ?1 AND expires <= unixepoch('now')
  AND typeof(?2) = 'text' AND length(?2) > 0
  AND typeof(?3) = 'text' AND length(?3) > 0
  AND typeof(?4) IN ('integer', 'real') AND ?4 = CAST(?4 AS INTEGER) AND ?4 BETWEEN 1 AND 300
ON CONFLICT (world_id, lease_id) DO NOTHING
RETURNING epoch, base_generation AS generation, initial_expires AS expires, unixepoch('now') AS server_now;
