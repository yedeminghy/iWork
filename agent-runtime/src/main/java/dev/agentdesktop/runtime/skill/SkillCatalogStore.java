package dev.agentdesktop.runtime.skill;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.persistence.LocalDatabase;
import dev.agentdesktop.runtime.skill.SkillModels.*;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;

final class SkillCatalogStore {
    private final LocalDatabase database;
    private final ObjectMapper mapper;

    SkillCatalogStore(LocalDatabase database, ObjectMapper mapper) {
        this.database = Objects.requireNonNull(database);
        this.mapper = Objects.requireNonNull(mapper);
    }

    record DiscoveredSkill(String id, String name, String directory, String description,
                           String license, String compatibility, Map<String, String> metadata,
                           String allowedTools, Map<String, Object> extensions,
                           String contentHash, List<String> warnings, String errorCode,
                           List<SkillResourceDescriptor> resources) {}

    record Entry(String id, String sourceId, SkillScope scope, String workspaceId, Path root,
                 long generation, String name, String directory, String description,
                 String license, String compatibility, Map<String, String> metadata,
                 String allowedTools, Map<String, Object> extensions, SkillStatus status,
                 boolean enabled, String contentHash, List<String> warnings,
                 String errorCode, String shadowedById) {}

    SkillRefreshResult replace(SkillScope scope, String workspaceId, Path root,
                               List<DiscoveredSkill> discoveries) {
        String normalizedWorkspace = scope == SkillScope.WORKSPACE ? workspaceId : "";
        String sourceId = stableId("src", scope + "|" + normalizedWorkspace + "|" + root);
        String now = Instant.now().toString();
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                long generation = ensureSource(connection, sourceId, scope, normalizedWorkspace, root, now) + 1;
                execute(connection, "UPDATE skill_entry SET status='MISSING',shadowed_by_id=NULL,updated_at=? WHERE source_id=?", now, sourceId);
                for (DiscoveredSkill skill : discoveries) {
                    execute(connection, "INSERT INTO skill_entry(id,source_id,name,relative_directory,description,license,compatibility,metadata_json,allowed_tools_text,extensions_json,status,enabled,content_hash,warning_codes_json,last_error_code,shadowed_by_id,discovered_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,1,?,?,?,?,?,?) " +
                                    "ON CONFLICT(id) DO UPDATE SET name=excluded.name,relative_directory=excluded.relative_directory,description=excluded.description,license=excluded.license,compatibility=excluded.compatibility,metadata_json=excluded.metadata_json,allowed_tools_text=excluded.allowed_tools_text,extensions_json=excluded.extensions_json,status=excluded.status,content_hash=excluded.content_hash,warning_codes_json=excluded.warning_codes_json,last_error_code=excluded.last_error_code,shadowed_by_id=NULL,updated_at=excluded.updated_at",
                            skill.id(), sourceId, skill.name(), skill.directory(), skill.description(), skill.license(), skill.compatibility(), json(skill.metadata()), skill.allowedTools(), json(skill.extensions()),
                            skill.errorCode() == null ? "VALID" : "INVALID", skill.contentHash(), json(skill.warnings()), skill.errorCode(), null, now, now);
                    execute(connection, "DELETE FROM skill_resource WHERE skill_id=?", skill.id());
                    for (SkillResourceDescriptor resource : skill.resources()) {
                        execute(connection, "INSERT INTO skill_resource(id,skill_id,relative_path,kind,media_type,byte_size,content_hash,executable_candidate) VALUES(?,?,?,?,?,?,?,?)",
                                resource.id(), skill.id(), resource.relativePath(), resource.kind(), resource.mediaType(), resource.byteSize(), resource.contentHash(), resource.executableCandidate() ? 1 : 0);
                    }
                }
                execute(connection, "UPDATE skill_source SET generation=?,last_scan_status='SUCCEEDED',last_error_code=NULL,last_scanned_at=?,updated_at=? WHERE id=?", generation, now, now, sourceId);
                resolveShadowing(connection, workspaceId);
                Counts counts = counts(connection, workspaceId);
                connection.commit();
                return new SkillRefreshResult(scope, generation, counts.valid, counts.invalid, counts.shadowed, counts.missing, "SUCCEEDED", null);
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (Exception exception) {
            markSourceFailure(sourceId, scope, normalizedWorkspace, root, now, "SKILL_SOURCE_UNAVAILABLE");
            throw new SkillException("SKILL_SOURCE_UNAVAILABLE", exception);
        }
    }

    SkillPage list(SkillQuery query, String workspaceId) {
        StringBuilder sql = new StringBuilder("SELECT e.*,s.scope,s.workspace_id,s.root_path,s.generation FROM skill_entry e JOIN skill_source s ON s.id=e.source_id WHERE (s.scope<>'WORKSPACE' OR s.workspace_id=?)");
        List<Object> values = new ArrayList<>(); values.add(workspaceId);
        if (query != null && query.scope() != null) { sql.append(" AND s.scope=?"); values.add(query.scope().name()); }
        if (query != null && query.status() != null) { sql.append(" AND e.status=?"); values.add(query.status().name()); }
        sql.append(" ORDER BY e.name,CASE s.scope WHEN 'WORKSPACE' THEN 1 WHEN 'USER' THEN 2 ELSE 3 END,e.id");
        List<SkillSummary> result = new ArrayList<>(); long generation = 0;
        try (Connection connection = database.open(); PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            bind(ps, values.toArray());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { Entry row = entry(rs); generation = Math.max(generation, row.generation()); result.add(summary(row)); }
            }
        } catch (SQLException exception) { throw new SkillException("SKILL_SOURCE_UNAVAILABLE", exception); }
        return new SkillPage(List.copyOf(result), generation);
    }

    Entry require(String id) {
        try (Connection connection = database.open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT e.*,s.scope,s.workspace_id,s.root_path,s.generation FROM skill_entry e JOIN skill_source s ON s.id=e.source_id WHERE e.id=?")) {
            ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return entry(rs); }
        } catch (SQLException exception) { throw new SkillException("SKILL_SOURCE_UNAVAILABLE", exception); }
        throw new SkillException("SKILL_NOT_FOUND");
    }

    List<SkillResourceDescriptor> resources(String skillId) {
        require(skillId);
        List<SkillResourceDescriptor> result = new ArrayList<>();
        try (Connection connection = database.open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT id,relative_path,kind,media_type,byte_size,content_hash,executable_candidate FROM skill_resource WHERE skill_id=? ORDER BY relative_path")) {
            ps.setString(1, skillId); try (ResultSet rs = ps.executeQuery()) { while (rs.next()) result.add(new SkillResourceDescriptor(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getInt(7) == 1)); }
        } catch (SQLException exception) { throw new SkillException("SKILL_SOURCE_UNAVAILABLE", exception); }
        return List.copyOf(result);
    }

    SkillResourceDescriptor requireResource(String skillId, String resourceId) {
        try (Connection connection = database.open(); PreparedStatement ps = connection.prepareStatement(
                "SELECT id,relative_path,kind,media_type,byte_size,content_hash,executable_candidate FROM skill_resource WHERE skill_id=? AND id=?")) {
            ps.setString(1, skillId); ps.setString(2, resourceId); try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return new SkillResourceDescriptor(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6), rs.getInt(7) == 1); }
        } catch (SQLException exception) { throw new SkillException("SKILL_SOURCE_UNAVAILABLE", exception); }
        throw new SkillException("SKILL_RESOURCE_NOT_FOUND");
    }

    SkillSummary setEnabled(String id, boolean enabled, String workspaceId) {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                if (execute(connection, "UPDATE skill_entry SET enabled=?,updated_at=? WHERE id=?", enabled ? 1 : 0, Instant.now().toString(), id) == 0)
                    throw new SkillException("SKILL_NOT_FOUND");
                resolveShadowing(connection, workspaceId); connection.commit();
            } catch (Exception exception) { connection.rollback(); throw exception; }
        } catch (SQLException exception) { throw new SkillException("SKILL_SOURCE_UNAVAILABLE", exception); }
        return summary(require(id));
    }

    void recordActivation(String turnId, Entry entry, ActivationReason reason) {
        try (Connection connection = database.open()) {
            execute(connection, "INSERT INTO skill_activation(turn_id,skill_id,activation_reason,content_hash,activated_at) VALUES(?,?,?,?,?) ON CONFLICT(turn_id,skill_id) DO NOTHING",
                    turnId, entry.id(), reason.name(), entry.contentHash(), Instant.now().toString());
        } catch (SQLException exception) { throw new SkillException("SKILL_SOURCE_UNAVAILABLE", exception); }
    }

    private void resolveShadowing(Connection connection, String workspaceId) throws SQLException {
        execute(connection, "UPDATE skill_entry SET status=CASE WHEN last_error_code IS NULL THEN 'VALID' ELSE 'INVALID' END,shadowed_by_id=NULL WHERE status<>'MISSING' AND source_id IN (SELECT id FROM skill_source WHERE scope<>'WORKSPACE' OR workspace_id=?)", workspaceId);
        String sql = "SELECT e.id,e.name,e.status,e.enabled,s.scope FROM skill_entry e JOIN skill_source s ON s.id=e.source_id WHERE e.status<>'MISSING' AND (s.scope<>'WORKSPACE' OR s.workspace_id=?) ORDER BY e.name,CASE s.scope WHEN 'WORKSPACE' THEN 1 WHEN 'USER' THEN 2 ELSE 3 END,e.id";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, workspaceId); try (ResultSet rs = ps.executeQuery()) {
                String name = null; String blocker = null;
                while (rs.next()) {
                    if (!Objects.equals(name, rs.getString("name"))) { name = rs.getString("name"); blocker = null; }
                    if (rs.getInt("enabled") == 0) continue;
                    if (blocker == null) blocker = rs.getString("id");
                    else execute(connection, "UPDATE skill_entry SET status='SHADOWED',shadowed_by_id=? WHERE id=?", blocker, rs.getString("id"));
                }
            }
        }
    }

    private long ensureSource(Connection connection, String id, SkillScope scope, String workspaceId, Path root, String now) throws SQLException {
        execute(connection, "INSERT INTO skill_source(id,scope,workspace_id,root_path,enabled,generation,last_scan_status,created_at,updated_at) VALUES(?,?,?,?,1,0,'NEVER',?,?) ON CONFLICT(id) DO NOTHING",
                id, scope.name(), workspaceId, root.toString(), now, now);
        try (PreparedStatement ps = connection.prepareStatement("SELECT generation FROM skill_source WHERE id=?")) { ps.setString(1, id); try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); } }
    }

    private void markSourceFailure(String id, SkillScope scope, String workspaceId, Path root, String now, String code) {
        try (Connection connection = database.open()) {
            ensureSource(connection, id, scope, workspaceId, root, now);
            execute(connection, "UPDATE skill_source SET last_scan_status='FAILED',last_error_code=?,last_scanned_at=?,updated_at=? WHERE id=?", code, now, now, id);
        } catch (SQLException ignored) { }
    }

    private Counts counts(Connection connection, String workspaceId) throws SQLException {
        int valid=0,invalid=0,shadowed=0,missing=0;
        try (PreparedStatement ps=connection.prepareStatement("SELECT e.status,COUNT(*) FROM skill_entry e JOIN skill_source s ON s.id=e.source_id WHERE s.scope<>'WORKSPACE' OR s.workspace_id=? GROUP BY e.status")) {
            ps.setString(1, workspaceId); try(ResultSet rs=ps.executeQuery()){while(rs.next()){int count=rs.getInt(2);switch(rs.getString(1)){case "VALID"->valid=count;case "INVALID"->invalid=count;case "SHADOWED"->shadowed=count;case "MISSING"->missing=count;default->{}}}}
        }
        return new Counts(valid,invalid,shadowed,missing);
    }

    private Entry entry(ResultSet rs) throws SQLException {
        return new Entry(rs.getString("id"),rs.getString("source_id"),SkillScope.valueOf(rs.getString("scope")),rs.getString("workspace_id"),Path.of(rs.getString("root_path")),rs.getLong("generation"),
                rs.getString("name"),rs.getString("relative_directory"),rs.getString("description"),rs.getString("license"),rs.getString("compatibility"),
                read(rs.getString("metadata_json"),new TypeReference<Map<String,String>>(){}),rs.getString("allowed_tools_text"),read(rs.getString("extensions_json"),new TypeReference<Map<String,Object>>(){}),
                SkillStatus.valueOf(rs.getString("status")),rs.getInt("enabled")==1,rs.getString("content_hash"),read(rs.getString("warning_codes_json"),new TypeReference<List<String>>(){}),rs.getString("last_error_code"),rs.getString("shadowed_by_id"));
    }

    static SkillSummary summary(Entry row) { return new SkillSummary(row.id(),row.name(),row.description(),row.scope(),row.status(),row.enabled(),row.compatibility(),row.warnings(),row.errorCode(),row.shadowedById(),row.generation()); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception exception) { throw new IllegalStateException(exception); } }
    private <T> T read(String value, TypeReference<T> type) { try { return mapper.readValue(value,type); } catch (Exception exception) { throw new SkillException("SKILL_INVALID_FRONTMATTER",exception); } }
    private static int execute(Connection c,String sql,Object...values)throws SQLException{try(PreparedStatement ps=c.prepareStatement(sql)){bind(ps,values);return ps.executeUpdate();}}
    private static void bind(PreparedStatement ps,Object...values)throws SQLException{for(int i=0;i<values.length;i++)ps.setObject(i+1,values[i]);}
    static String stableId(String prefix,String value){try{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));return prefix+"_"+Base64.getUrlEncoder().withoutPadding().encodeToString(hash).substring(0,32);}catch(Exception e){throw new IllegalStateException(e);}}
    private record Counts(int valid,int invalid,int shadowed,int missing){}
}
