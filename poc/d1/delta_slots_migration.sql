-- Fixed Drive files that hosts overwrite with each handoff delta instead of creating new ones.
-- Every member grants each slot once; afterwards a handoff needs no browser consent.
-- Apply BEFORE deploying the matching Worker (snapshot_status.sql reads this table).
CREATE TABLE IF NOT EXISTS delta_slots (
    world_id TEXT NOT NULL,
    object_id TEXT NOT NULL,
    PRIMARY KEY (world_id, object_id)
);
