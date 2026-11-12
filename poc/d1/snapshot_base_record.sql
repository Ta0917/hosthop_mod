-- Bind world ID, object ID, base object ID, digest. Only called after the caller's commit receipt was verified.
-- A delta slot is overwritten by later handoffs, so the record follows the object's current content.
INSERT INTO snapshot_bases (world_id, object_id, base_object_id)
SELECT ?1, ?2, ?3
WHERE EXISTS (SELECT 1 FROM worlds w WHERE w.id = ?1 AND w.object_id = ?2 AND w.digest = ?4)
ON CONFLICT (world_id, object_id) DO UPDATE SET base_object_id = excluded.base_object_id;
