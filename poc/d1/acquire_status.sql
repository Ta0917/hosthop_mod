-- Bind ?1 world_id, ?2 lease_id. Caller must authenticate and compare owner
-- and duration with the original request. Only active=1 may be used to host.
SELECT g.owner, g.duration_seconds, g.epoch, g.base_generation, w.expires,
       CASE WHEN w.lease_id = g.lease_id AND w.owner = g.owner
                 AND w.epoch = g.epoch AND w.expires > unixepoch('now')
            THEN 1 ELSE 0 END AS active, unixepoch('now') AS server_now
FROM lease_grants g JOIN worlds w ON w.id = g.world_id
WHERE g.world_id = ?1 AND g.lease_id = ?2;
