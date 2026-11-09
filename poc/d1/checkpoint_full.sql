SELECT COALESCE((SELECT created_at FROM world_checkpoints WHERE world_id = ?1 AND kind = 'full' ORDER BY created_at DESC, rowid DESC LIMIT 1), 0) AS created_at,
       unixepoch('now') AS server_now;
