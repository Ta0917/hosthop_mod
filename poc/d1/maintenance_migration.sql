CREATE TABLE IF NOT EXISTS world_maintenance (
  world_id TEXT PRIMARY KEY REFERENCES worlds(id),
  enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
  revision INTEGER NOT NULL DEFAULT 0 CHECK (revision >= 0)
);
CREATE TRIGGER IF NOT EXISTS reject_maintenance_grant BEFORE INSERT ON lease_grants
WHEN EXISTS (SELECT 1 FROM world_maintenance m WHERE m.world_id = NEW.world_id AND m.enabled = 1)
BEGIN
  SELECT RAISE(IGNORE);
END;
