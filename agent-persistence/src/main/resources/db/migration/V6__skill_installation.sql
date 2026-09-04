CREATE TABLE skill_managed_install (
  id TEXT PRIMARY KEY,
  skill_name TEXT NOT NULL UNIQUE,
  scope TEXT NOT NULL CHECK (scope = 'USER'),
  managed_by_app INTEGER NOT NULL CHECK (managed_by_app IN (0,1)),
  current_revision_id TEXT,
  backup_revision_id TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE skill_install_revision (
  id TEXT PRIMARY KEY,
  installation_id TEXT NOT NULL,
  revision_state TEXT NOT NULL CHECK (revision_state IN ('ACTIVE','BACKUP','REMOVED')),
  source_type TEXT NOT NULL CHECK (source_type IN ('LOCAL_DIRECTORY','GITHUB')),
  repository_url TEXT,
  requested_ref TEXT,
  resolved_commit_sha TEXT,
  source_subdirectory TEXT,
  package_hash TEXT NOT NULL,
  skill_content_hash TEXT NOT NULL,
  backup_key TEXT,
  installed_at TEXT NOT NULL,
  FOREIGN KEY(installation_id) REFERENCES skill_managed_install(id) ON DELETE CASCADE
);

CREATE TABLE skill_install_transaction (
  id TEXT PRIMARY KEY,
  skill_name TEXT NOT NULL,
  operation TEXT NOT NULL CHECK (operation IN ('INSTALL','REPLACE','REMOVE','ROLLBACK')),
  state TEXT NOT NULL CHECK (state IN ('PREPARING','BACKED_UP','MOVED','COMMITTED','ROLLED_BACK','FAILED')),
  expected_package_hash TEXT,
  previous_package_hash TEXT,
  last_error_code TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE INDEX idx_skill_revision_installation_state
  ON skill_install_revision(installation_id, revision_state, installed_at DESC);
CREATE INDEX idx_skill_install_transaction_state
  ON skill_install_transaction(state, updated_at);
