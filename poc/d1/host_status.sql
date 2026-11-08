-- Bind world. Expired ownership remains held until explicit safe release or abort.
SELECT CASE WHEN owner IS NULL AND lease_id IS NULL THEN 0 ELSE 1 END AS held,
       CASE WHEN expires > unixepoch('now') THEN endpoint ELSE NULL END AS endpoint,
       endpoint_revision, epoch, generation, expires, unixepoch('now') AS server_now
FROM worlds WHERE id = ?1;
