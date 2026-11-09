SELECT COALESCE((SELECT keep_delta FROM backup_settings WHERE world_id = ?1), 4) AS keep_delta;
