-- Bind world and lease ID. Caller authenticates and compares all fields.
SELECT world_id, owner, lease_id, epoch, operation_id, result_generation
FROM lease_releases WHERE world_id = ?1 AND lease_id = ?2;
