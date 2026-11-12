-- Four latest committed generations; historical objects are never deleted here.
-- A generation whose delta slot a later generation overwrote is no longer restorable and is left out.
SELECT c.result_generation AS generation, c.digest, c.object_id, c.object_size
FROM committed_operations c
WHERE c.world_id = ?1
  AND NOT EXISTS (SELECT 1 FROM committed_operations later WHERE later.world_id = c.world_id
    AND later.object_id = c.object_id AND later.result_generation > c.result_generation)
ORDER BY c.result_generation DESC LIMIT 4;
