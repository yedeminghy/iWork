CREATE TABLE local_user (
  id TEXT PRIMARY KEY, display_name TEXT NOT NULL,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE TABLE workspace (
  id TEXT PRIMARY KEY, local_user_id TEXT NOT NULL REFERENCES local_user(id),
  name TEXT NOT NULL, root_path TEXT NOT NULL UNIQUE,
  is_default INTEGER NOT NULL CHECK (is_default IN (0, 1)), created_at TEXT NOT NULL
);
CREATE TABLE thread (
  id TEXT PRIMARY KEY, local_user_id TEXT NOT NULL REFERENCES local_user(id),
  workspace_id TEXT NOT NULL REFERENCES workspace(id), title TEXT NOT NULL,
  model_profile_id TEXT NOT NULL, status TEXT NOT NULL,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL, archived_at TEXT
);
CREATE TABLE turn (
  id TEXT PRIMARY KEY, thread_id TEXT NOT NULL REFERENCES thread(id),
  run_id TEXT NOT NULL UNIQUE, status TEXT NOT NULL, model_profile_id TEXT NOT NULL,
  iteration_count INTEGER NOT NULL DEFAULT 0, cancel_requested_at TEXT,
  started_at TEXT NOT NULL, finished_at TEXT, error_code TEXT
);
CREATE TABLE item (
  id TEXT PRIMARY KEY, thread_id TEXT NOT NULL REFERENCES thread(id),
  turn_id TEXT REFERENCES turn(id), sequence INTEGER NOT NULL, type TEXT NOT NULL,
  visibility TEXT NOT NULL, payload_json TEXT NOT NULL, created_at TEXT NOT NULL,
  UNIQUE(turn_id, sequence)
);
CREATE TABLE approval (
  id TEXT PRIMARY KEY, turn_id TEXT NOT NULL REFERENCES turn(id),
  capability TEXT NOT NULL, request_json TEXT NOT NULL, decision TEXT NOT NULL,
  expires_at TEXT NOT NULL, resolved_at TEXT,
  resolved_by_user_id TEXT REFERENCES local_user(id)
);
CREATE INDEX idx_thread_updated_at ON thread(updated_at DESC);
CREATE INDEX idx_item_thread_sequence ON item(thread_id, sequence);
CREATE INDEX idx_turn_thread_started_at ON turn(thread_id, started_at DESC);
