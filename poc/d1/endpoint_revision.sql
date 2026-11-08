-- Bind world, owner, lease, epoch. For the current owner to recover revision.
SELECT endpoint_revision, endpoint, epoch, expires, unixepoch('now') AS server_now
FROM worlds WHERE id = ?1 AND owner = ?2 AND lease_id = ?3 AND epoch = ?4
  AND expires > unixepoch('now');
