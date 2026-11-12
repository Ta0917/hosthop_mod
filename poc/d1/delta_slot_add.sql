-- Bind world ID, newly committed object ID, digest. Only after a verified commit receipt.
-- A world keeps at most two slots, and only the current snapshot can become one.
INSERT OR IGNORE INTO delta_slots (world_id, object_id)
SELECT ?1, ?2
WHERE EXISTS (SELECT 1 FROM worlds w WHERE w.id = ?1 AND w.object_id = ?2 AND w.digest = ?3)
  AND (SELECT COUNT(*) FROM delta_slots s WHERE s.world_id = ?1) < 2;
