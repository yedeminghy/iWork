CREATE TABLE skill_source (
  id TEXT PRIMARY KEY,
  scope TEXT NOT NULL,
  workspace_id TEXT NOT NULL DEFAULT '',
  root_path TEXT NOT NULL,
  enabled INTEGER NOT NULL CHECK (enabled IN (0,1)),
  generation INTEGER NOT NULL DEFAULT 0,
  last_scan_status TEXT NOT NULL,
  last_error_code TEXT,
  last_scanned_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(scope, workspace_id, root_path),
  CHECK (scope IN ('BUILTIN','USER','WORKSPACE'))
);

CREATE TABLE skill_entry (
  id TEXT PRIMARY KEY,
  source_id TEXT NOT NULL,
  name TEXT NOT NULL,
  relative_directory TEXT NOT NULL,
  description TEXT NOT NULL,
  license TEXT,
  compatibility TEXT,
  metadata_json TEXT NOT NULL,
  allowed_tools_text TEXT,
  extensions_json TEXT NOT NULL,
  status TEXT NOT NULL,
  enabled INTEGER NOT NULL CHECK (enabled IN (0,1)),
  content_hash TEXT NOT NULL,
  warning_codes_json TEXT NOT NULL,
  last_error_code TEXT,
  shadowed_by_id TEXT,
  discovered_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(source_id, relative_directory),
  FOREIGN KEY(source_id) REFERENCES skill_source(id)
);

CREATE TABLE skill_resource (
  id TEXT PRIMARY KEY,
  skill_id TEXT NOT NULL,
  relative_path TEXT NOT NULL,
  kind TEXT NOT NULL,
  media_type TEXT,
  byte_size INTEGER NOT NULL,
  content_hash TEXT,
  executable_candidate INTEGER NOT NULL CHECK (executable_candidate IN (0,1)),
  UNIQUE(skill_id, relative_path),
  FOREIGN KEY(skill_id) REFERENCES skill_entry(id)
);

CREATE TABLE skill_activation (
  turn_id TEXT NOT NULL,
  skill_id TEXT NOT NULL,
  activation_reason TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  activated_at TEXT NOT NULL,
  PRIMARY KEY(turn_id, skill_id),
  FOREIGN KEY(turn_id) REFERENCES turn(id),
  FOREIGN KEY(skill_id) REFERENCES skill_entry(id)
);

CREATE INDEX idx_skill_entry_status_name ON skill_entry(enabled,status,name);
CREATE INDEX idx_skill_resource_skill_kind ON skill_resource(skill_id,kind,relative_path);
