-- Bind world ID, newly committed object ID, replaced slot ID, digest. Only after a verified commit receipt.
-- Drops a slot the committing host could not write, once its replacement is the current snapshot.
DELETE FROM delta_slots
WHERE world_id = ?1 AND object_id = ?3 AND ?3 <> ?2
  AND EXISTS (SELECT 1 FROM worlds w WHERE w.id = ?1 AND w.object_id = ?2 AND w.digest = ?4);
