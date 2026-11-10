CREATE TABLE IF NOT EXISTS host_compatibility (
  world_id TEXT PRIMARY KEY REFERENCES worlds(id),
  lease_id TEXT NOT NULL,
  epoch INTEGER NOT NULL,
  manifest TEXT NOT NULL CHECK(length(manifest) BETWEEN 2 AND 100000)
);
