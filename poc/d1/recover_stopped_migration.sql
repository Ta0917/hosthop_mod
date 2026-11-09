CREATE TABLE IF NOT EXISTS stopped_recoveries (
    world_id TEXT NOT NULL, operation_id TEXT NOT NULL, owner TEXT NOT NULL,
    lease_id TEXT NOT NULL, epoch INTEGER NOT NULL, previous_generation INTEGER NOT NULL,
    digest TEXT NOT NULL, object_id TEXT NOT NULL, object_size INTEGER NOT NULL,
    PRIMARY KEY (world_id, operation_id)
);

CREATE TRIGGER IF NOT EXISTS apply_stopped_recovery AFTER INSERT ON stopped_recoveries
BEGIN
    -- This temporary extension is never visible outside the transaction. The
    -- release below clears ownership before any other request can observe it.
    UPDATE worlds SET expires = unixepoch('now') + 60, endpoint = NULL
    WHERE id = NEW.world_id AND owner = NEW.owner AND lease_id = NEW.lease_id
      AND epoch = NEW.epoch AND generation = NEW.previous_generation AND expires <= unixepoch('now');
    SELECT CASE WHEN changes() != 1 THEN RAISE(ABORT, 'stale stopped recovery') END;
    INSERT INTO committed_operations (world_id, operation_id, owner, lease_id, epoch, previous_generation, digest, object_id, object_size, result_generation)
    VALUES (NEW.world_id, NEW.operation_id, NEW.owner, NEW.lease_id, NEW.epoch, NEW.previous_generation, NEW.digest, NEW.object_id, NEW.object_size, NEW.previous_generation + 1);
    INSERT INTO lease_releases (world_id, lease_id, owner, epoch, operation_id, result_generation)
    VALUES (NEW.world_id, NEW.lease_id, NEW.owner, NEW.epoch, NEW.operation_id, NEW.previous_generation + 1);
END;
