-- Bind ?1 world_id, ?2 operation_id. The caller must authenticate this read,
-- query the primary and compare every field with the persisted original request.
-- An absent record alone does not prove that an in-flight request cannot commit.
SELECT world_id, operation_id, owner, lease_id, epoch, previous_generation, digest, result_generation
FROM committed_operations WHERE world_id = ?1 AND operation_id = ?2;
