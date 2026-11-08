-- Bind world. Authenticate membership and read from the authoritative primary.
-- A matching lease does not prove that the relay is reachable or authentic.
SELECT endpoint, endpoint_revision, epoch, generation, expires, unixepoch('now') AS server_now
FROM worlds WHERE id = ?1 AND expires > unixepoch('now') AND endpoint IS NOT NULL;
