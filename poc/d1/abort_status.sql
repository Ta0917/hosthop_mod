SELECT world_id, owner, lease_id, epoch, base_generation
FROM lease_aborts WHERE world_id = ?1 AND lease_id = ?2;
