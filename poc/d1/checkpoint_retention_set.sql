INSERT INTO backup_settings(world_id, keep_delta) SELECT id, ?2 FROM worlds WHERE id = ?1 AND ?2 BETWEEN 1 AND 28
ON CONFLICT(world_id) DO UPDATE SET keep_delta = excluded.keep_delta
RETURNING keep_delta;
