package dev.agentdesktop.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Durable boundary for non-sensitive MCP configuration and the sanitized tool catalog. */
public final class McpCatalogRepository {
    private final LocalDatabase database;
    private final ObjectMapper mapper;

    public McpCatalogRepository(LocalDatabase database, ObjectMapper mapper) {
        this.database = Objects.requireNonNull(database);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public synchronized ServerRow save(ServerRow row) {
        return transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO mcp_server(id,name,transport,status,enabled,endpoint,executable,arguments_json,
                      working_directory,env_allowlist_json,credential_ref,reconnect_policy_json,last_error_code,
                      last_diagnostic,last_connected_at,catalog_version,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET name=excluded.name,transport=excluded.transport,
                      status=excluded.status,enabled=excluded.enabled,endpoint=excluded.endpoint,
                      executable=excluded.executable,arguments_json=excluded.arguments_json,
                      working_directory=excluded.working_directory,env_allowlist_json=excluded.env_allowlist_json,
                      credential_ref=excluded.credential_ref,reconnect_policy_json=excluded.reconnect_policy_json,
                      updated_at=excluded.updated_at
                    WHERE mcp_server.deleted_at IS NULL
                    """)) {
                bindServer(statement, row);
                statement.executeUpdate();
            }
            return find(connection, row.id());
        });
    }

    public synchronized ServerRow updateStatus(String id, String status, String errorCode,
                                               String diagnostic, Instant connectedAt, long catalogVersion) {
        return transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE mcp_server SET status=?,last_error_code=?,last_diagnostic=?,last_connected_at=?,
                      catalog_version=?,updated_at=? WHERE id=? AND deleted_at IS NULL
                    """)) {
                statement.setString(1, status); statement.setString(2, errorCode);
                statement.setString(3, diagnostic); statement.setString(4, connectedAt == null ? null : connectedAt.toString());
                statement.setLong(5, catalogVersion); statement.setString(6, Instant.now().toString()); statement.setString(7, id);
                if (statement.executeUpdate() != 1) throw new DomainException("MCP_SERVER_NOT_FOUND");
            }
            return find(connection, id);
        });
    }

    public synchronized void replaceTools(String serverId, long version, List<ToolRow> tools) {
        transaction(connection -> {
            if (find(connection, serverId) == null) throw new DomainException("MCP_SERVER_NOT_FOUND");
            try (PreparedStatement disable = connection.prepareStatement(
                    "UPDATE mcp_tool SET enabled=0,updated_at=? WHERE server_id=?")) {
                disable.setString(1, Instant.now().toString()); disable.setString(2, serverId); disable.executeUpdate();
            }
            for (ToolRow tool : tools) upsertTool(connection, tool);
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE mcp_server SET catalog_version=?,updated_at=? WHERE id=? AND deleted_at IS NULL")) {
                update.setLong(1, version); update.setString(2, Instant.now().toString()); update.setString(3, serverId); update.executeUpdate();
            }
            return null;
        });
    }

    public ServerRow get(String id) {
        try (Connection connection = database.open()) {
            ServerRow row = find(connection, id);
            if (row == null) throw new DomainException("MCP_SERVER_NOT_FOUND");
            return row;
        } catch (SQLException exception) { throw new PersistenceException(exception); }
    }

    /**
     * Soft-deletes a server while retaining its sanitized catalog for historical audit.
     * The returned row is the pre-delete active record so callers can finish external
     * credential cleanup without exposing the credential value.
     */
    public synchronized ServerRow softDelete(String id) {
        return transaction(connection -> {
            ServerRow existing = find(connection, id);
            if (existing == null) throw new DomainException("MCP_SERVER_NOT_FOUND");
            String now = Instant.now().toString();
            try (PreparedStatement disableTools = connection.prepareStatement(
                    "UPDATE mcp_tool SET enabled=0,updated_at=? WHERE server_id=?")) {
                disableTools.setString(1, now);
                disableTools.setString(2, id);
                disableTools.executeUpdate();
            }
            try (PreparedStatement deleteServer = connection.prepareStatement("""
                    UPDATE mcp_server SET status='DELETED',enabled=0,credential_ref=NULL,
                      last_error_code=NULL,last_diagnostic='Deleted',updated_at=?,deleted_at=?
                    WHERE id=? AND deleted_at IS NULL
                    """)) {
                deleteServer.setString(1, now);
                deleteServer.setString(2, now);
                deleteServer.setString(3, id);
                if (deleteServer.executeUpdate() != 1) throw new DomainException("MCP_SERVER_NOT_FOUND");
            }
            return existing;
        });
    }

    public List<ServerRow> list(int limit) {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM mcp_server WHERE deleted_at IS NULL ORDER BY updated_at DESC,id DESC LIMIT ?")) {
            statement.setInt(1, Math.max(1, Math.min(100, limit)));
            try (ResultSet result = statement.executeQuery()) {
                List<ServerRow> rows = new ArrayList<>(); while (result.next()) rows.add(server(result)); return List.copyOf(rows);
            }
        } catch (SQLException exception) { throw new PersistenceException(exception); }
    }

    /** Includes soft-deleted rows so a user deletion is not undone by first-run defaults. */
    public boolean hasServerNamedIncludingDeleted(String name) {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM mcp_server WHERE name=? LIMIT 1")) {
            statement.setString(1, name);
            try (ResultSet result = statement.executeQuery()) { return result.next(); }
        } catch (SQLException exception) { throw new PersistenceException(exception); }
    }

    public List<ToolRow> listTools(String serverId) {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT t.* FROM mcp_tool t JOIN mcp_server s ON s.id=t.server_id " +
                        "WHERE t.server_id=? AND s.deleted_at IS NULL ORDER BY t.name")) {
            statement.setString(1, serverId);
            try (ResultSet result = statement.executeQuery()) {
                List<ToolRow> rows = new ArrayList<>(); while (result.next()) rows.add(tool(result)); return List.copyOf(rows);
            }
        } catch (SQLException exception) { throw new PersistenceException(exception); }
    }

    private void upsertTool(Connection connection, ToolRow row) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO mcp_tool(id,server_id,name,description,input_schema_json,risk_class,enabled,
                  catalog_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(server_id,name) DO UPDATE SET description=excluded.description,
                  input_schema_json=excluded.input_schema_json,risk_class=excluded.risk_class,
                  enabled=excluded.enabled,catalog_version=excluded.catalog_version,updated_at=excluded.updated_at
                """)) {
            statement.setString(1,row.id()); statement.setString(2,row.serverId()); statement.setString(3,row.name());
            statement.setString(4,row.description()); statement.setString(5,row.inputSchemaJson());
            statement.setString(6,row.riskClass()); statement.setInt(7,row.enabled()?1:0); statement.setLong(8,row.catalogVersion());
            statement.setString(9,row.createdAt()); statement.setString(10,row.updatedAt()); statement.executeUpdate();
        }
    }

    private void bindServer(PreparedStatement s, ServerRow r) throws SQLException {
        s.setString(1,r.id()); s.setString(2,r.name()); s.setString(3,r.transport()); s.setString(4,r.status());
        s.setInt(5,r.enabled()?1:0); s.setString(6,r.endpoint()); s.setString(7,r.executable());
        s.setString(8,json(r.arguments())); s.setString(9,r.workingDirectory()); s.setString(10,json(r.allowedEnvironmentNames()));
        s.setString(11,r.credentialRef()); s.setString(12,r.reconnectPolicyJson()); s.setString(13,r.lastErrorCode());
        s.setString(14,r.lastDiagnostic()); s.setString(15,r.lastConnectedAt()); s.setLong(16,r.catalogVersion());
        s.setString(17,r.createdAt()); s.setString(18,r.updatedAt());
    }

    private ServerRow find(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM mcp_server WHERE id=? AND deleted_at IS NULL")) {
            statement.setString(1,id); try (ResultSet result=statement.executeQuery()) { return result.next()?server(result):null; }
        }
    }

    private ServerRow server(ResultSet r) throws SQLException {
        return new ServerRow(r.getString("id"),r.getString("name"),r.getString("transport"),r.getString("status"),
                r.getInt("enabled")!=0,r.getString("endpoint"),r.getString("executable"),strings(r.getString("arguments_json")),
                r.getString("working_directory"),strings(r.getString("env_allowlist_json")),r.getString("credential_ref"),
                r.getString("reconnect_policy_json"),r.getString("last_error_code"),r.getString("last_diagnostic"),
                r.getString("last_connected_at"),r.getLong("catalog_version"),r.getString("created_at"),r.getString("updated_at"));
    }

    private static ToolRow tool(ResultSet r) throws SQLException {
        return new ToolRow(r.getString("id"),r.getString("server_id"),r.getString("name"),r.getString("description"),
                r.getString("input_schema_json"),r.getString("risk_class"),r.getInt("enabled")!=0,
                r.getLong("catalog_version"),r.getString("created_at"),r.getString("updated_at"));
    }

    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new PersistenceException(e); } }
    private List<String> strings(String json) { try { return mapper.readValue(json,new TypeReference<>(){}); } catch (Exception e) { throw new PersistenceException(e); } }
    private <T> T transaction(SqlWork<T> work) {
        try (Connection connection=database.open()) { connection.setAutoCommit(false); try { T value=work.run(connection); connection.commit(); return value; }
        catch(Exception e){connection.rollback();if(e instanceof RuntimeException runtime)throw runtime;throw new PersistenceException(e);}}
        catch(SQLException e){throw new PersistenceException(e);}
    }
    @FunctionalInterface private interface SqlWork<T>{T run(Connection connection)throws Exception;}

    public record ServerRow(String id,String name,String transport,String status,boolean enabled,String endpoint,
                            String executable,List<String> arguments,String workingDirectory,List<String> allowedEnvironmentNames,
                            String credentialRef,String reconnectPolicyJson,String lastErrorCode,String lastDiagnostic,
                            String lastConnectedAt,long catalogVersion,String createdAt,String updatedAt) {}
    public record ToolRow(String id,String serverId,String name,String description,String inputSchemaJson,String riskClass,
                          boolean enabled,long catalogVersion,String createdAt,String updatedAt) {}
    public static final class DomainException extends RuntimeException { public DomainException(String code){super(code);} public String code(){return getMessage();} }
    public static final class PersistenceException extends RuntimeException { public PersistenceException(Throwable cause){super("MCP catalog persistence failed",cause);} }
}
