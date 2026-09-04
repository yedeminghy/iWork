CREATE TABLE provider_profile_state (
  profile_id TEXT PRIMARY KEY,
  enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
  status TEXT NOT NULL,
  observed_capabilities_json TEXT NOT NULL,
  last_error_code TEXT,
  last_checked_at TEXT,
  catalog_version TEXT NOT NULL
);

ALTER TABLE turn ADD COLUMN retry_of_turn_id TEXT REFERENCES turn(id);
CREATE INDEX idx_turn_retry_of ON turn(retry_of_turn_id);
