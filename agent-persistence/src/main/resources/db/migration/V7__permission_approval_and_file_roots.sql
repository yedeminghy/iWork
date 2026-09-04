ALTER TABLE approval ADD COLUMN thread_id TEXT REFERENCES thread(id);
ALTER TABLE approval ADD COLUMN call_id TEXT;
ALTER TABLE approval ADD COLUMN tool_id TEXT;
ALTER TABLE approval ADD COLUMN capability_class TEXT;
ALTER TABLE approval ADD COLUMN risk_level TEXT;
ALTER TABLE approval ADD COLUMN request_fingerprint TEXT;
ALTER TABLE approval ADD COLUMN subject_fingerprint TEXT;
ALTER TABLE approval ADD COLUMN arguments_hash TEXT;
ALTER TABLE approval ADD COLUMN resource_scope_hash TEXT;
ALTER TABLE approval ADD COLUMN resolution_code TEXT;
ALTER TABLE approval ADD COLUMN created_at TEXT;

CREATE UNIQUE INDEX uq_approval_call ON approval(call_id);
CREATE UNIQUE INDEX uq_approval_pending_turn
  ON approval(turn_id) WHERE decision = 'PENDING';
CREATE INDEX idx_approval_turn_created ON approval(turn_id, created_at DESC);

CREATE TABLE file_root (
  id TEXT PRIMARY KEY,
  workspace_id TEXT NOT NULL REFERENCES workspace(id),
  label TEXT NOT NULL,
  canonical_path TEXT NOT NULL,
  canonical_path_hash TEXT NOT NULL,
  root_identity TEXT,
  mode TEXT NOT NULL CHECK (mode IN ('READ_ONLY','READ_WRITE')),
  enabled INTEGER NOT NULL CHECK (enabled IN (0,1)),
  is_default INTEGER NOT NULL DEFAULT 0 CHECK (is_default IN (0,1)),
  version INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(workspace_id, canonical_path)
);

CREATE INDEX idx_file_root_workspace_enabled
  ON file_root(workspace_id, enabled, label);

CREATE TABLE permission_audit (
  id TEXT PRIMARY KEY,
  thread_id TEXT,
  turn_id TEXT,
  approval_id TEXT,
  call_id TEXT,
  action TEXT NOT NULL,
  capability_class TEXT NOT NULL,
  subject_id TEXT,
  subject_fingerprint TEXT,
  resource_scope_hash TEXT,
  decision TEXT NOT NULL,
  error_code TEXT,
  created_at TEXT NOT NULL
);

CREATE INDEX idx_permission_audit_turn_created
  ON permission_audit(turn_id, created_at DESC);
