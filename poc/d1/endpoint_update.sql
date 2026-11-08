-- Bind world, owner, lease, epoch, expected revision, endpoint (or NULL to revoke).
-- Caller validates addresses and authenticates ownership. Every update changes
-- the revision, preventing delayed callbacks from overwriting newer state.
UPDATE worlds SET endpoint = ?6, endpoint_revision = endpoint_revision + 1
WHERE id = ?1 AND owner = ?2 AND lease_id = ?3 AND epoch = ?4
  AND expires > unixepoch('now') AND endpoint_revision = ?5
  AND (?6 IS NULL OR (typeof(?6) = 'text' AND length(?6) BETWEEN 1 AND 253))
RETURNING endpoint_revision, endpoint, epoch, expires, unixepoch('now') AS server_now;
