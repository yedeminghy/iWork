CREATE TABLE mcp_server_v4 (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  transport TEXT NOT NULL,
  status TEXT NOT NULL,
  enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
  endpoint TEXT,
  executable TEXT,
  arguments_json TEXT,
  working_directory TEXT,
  env_allowlist_json TEXT NOT NULL,
  credential_ref TEXT,
  reconnect_policy_json TEXT NOT NULL,
  last_error_code TEXT,
  last_diagnostic TEXT,
  last_connected_at TEXT,
  catalog_version INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  deleted_at TEXT,
  CHECK (transport IN ('STDIO', 'STREAMABLE_HTTP')),
  CHECK ((transport = 'STDIO' AND executable IS NOT NULL AND endpoint IS NULL)
      OR (transport = 'STREAMABLE_HTTP' AND endpoint IS NOT NULL AND executable IS NULL))
);

INSERT INTO mcp_server_v4 (
  id,name,transport,status,enabled,endpoint,executable,arguments_json,working_directory,
  env_allowlist_json,credential_ref,reconnect_policy_json,last_error_code,last_diagnostic,
  last_connected_at,catalog_version,created_at,updated_at,deleted_at
)
SELECT id,name,transport,status,enabled,endpoint,executable,arguments_json,working_directory,
  env_allowlist_json,credential_ref,reconnect_policy_json,last_error_code,last_diagnostic,
  last_connected_at,catalog_version,created_at,updated_at,NULL
FROM mcp_server;

CREATE TABLE mcp_tool_v4 (
  id TEXT PRIMARY KEY,
  server_id TEXT NOT NULL,
  name TEXT NOT NULL,
  description TEXT NOT NULL,
  input_schema_json TEXT NOT NULL,
  risk_class TEXT NOT NULL,
  enabled INTEGER NOT NULL CHECK (enabled IN (0, 1)),
  catalog_version INTEGER NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(server_id, name),
  FOREIGN KEY(server_id) REFERENCES mcp_server_v4(id)
);

INSERT INTO mcp_tool_v4
SELECT id,server_id,name,description,input_schema_json,risk_class,enabled,catalog_version,created_at,updated_at
FROM mcp_tool;

DROP TABLE mcp_tool;
DROP TABLE mcp_server;
ALTER TABLE mcp_server_v4 RENAME TO mcp_server;
ALTER TABLE mcp_tool_v4 RENAME TO mcp_tool;

CREATE UNIQUE INDEX uq_mcp_server_active_name ON mcp_server(name) WHERE deleted_at IS NULL;
CREATE INDEX idx_mcp_server_enabled_status ON mcp_server(enabled, status, updated_at DESC);
CREATE INDEX idx_mcp_tool_server_enabled ON mcp_tool(server_id, enabled, name);
