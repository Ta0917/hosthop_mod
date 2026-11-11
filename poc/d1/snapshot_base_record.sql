-- Bind world ID, object ID, base object ID. Only called after the caller's commit receipt was verified.
INSERT OR IGNORE INTO snapshot_bases (world_id, object_id, base_object_id)
VALUES (?1, ?2, ?3);
