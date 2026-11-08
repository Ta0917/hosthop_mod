-- Experimental local schema. No authentication or verified object storage.
CREATE TABLE worlds (
    id TEXT PRIMARY KEY,
    owner TEXT,
    lease_id TEXT,
    epoch INTEGER NOT NULL DEFAULT 0,
    expires INTEGER NOT NULL DEFAULT 0,
    generation INTEGER NOT NULL DEFAULT 0,
    digest TEXT
);

CREATE TABLE lease_grants (
    world_id TEXT NOT NULL,
    lease_id TEXT NOT NULL,
    owner TEXT NOT NULL,
    epoch INTEGER NOT NULL,
    base_generation INTEGER NOT NULL,
    initial_expires INTEGER NOT NULL,
    duration_seconds INTEGER NOT NULL,
    PRIMARY KEY (world_id, lease_id),
    UNIQUE (world_id, epoch)
);

CREATE TRIGGER apply_lease_grant AFTER INSERT ON lease_grants
BEGIN
    UPDATE worlds SET owner = NEW.owner, lease_id = NEW.lease_id,
      epoch = NEW.epoch, expires = NEW.initial_expires
    WHERE id = NEW.world_id AND epoch = NEW.epoch - 1
      AND generation = NEW.base_generation AND expires <= unixepoch('now');
    SELECT CASE WHEN changes() != 1 THEN RAISE(ABORT, 'stale lease grant') END;
END;

CREATE TABLE committed_operations (
    world_id TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    owner TEXT NOT NULL,
    lease_id TEXT NOT NULL,
    epoch INTEGER NOT NULL,
    previous_generation INTEGER NOT NULL,
    digest TEXT NOT NULL,
    result_generation INTEGER NOT NULL,
    PRIMARY KEY (world_id, operation_id),
    UNIQUE (world_id, result_generation)
);

CREATE TRIGGER apply_committed_operation AFTER INSERT ON committed_operations
BEGIN
    UPDATE worlds SET generation = NEW.result_generation, digest = NEW.digest
    WHERE id = NEW.world_id AND owner = NEW.owner AND lease_id = NEW.lease_id AND epoch = NEW.epoch
      AND generation = NEW.previous_generation AND expires > unixepoch('now');
    SELECT CASE WHEN changes() != 1 THEN RAISE(ABORT, 'stale commit') END;
END;
