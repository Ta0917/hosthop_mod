-- Bind world ID; membership authentication must be enforced by the caller.
SELECT generation, digest, object_id, object_size FROM worlds WHERE id = ?1;
