CREATE TABLE IF NOT EXISTS distributed_mods (
  world_id TEXT PRIMARY KEY REFERENCES worlds(id),
  revision INTEGER NOT NULL CHECK (revision >= 1),
  manifest TEXT NOT NULL CHECK (length(manifest) BETWEEN 2 AND 60000),
  signature TEXT NOT NULL CHECK (length(signature) BETWEEN 1 AND 200)
);
