-- Lets guests learn, from the authority, which full snapshot the newest delta depends on,
-- so one browser consent can cover both files. Apply BEFORE deploying the matching Worker.
CREATE TABLE IF NOT EXISTS snapshot_bases (
    world_id TEXT NOT NULL,
    object_id TEXT NOT NULL,
    base_object_id TEXT NOT NULL,
    PRIMARY KEY (world_id, object_id)
);
