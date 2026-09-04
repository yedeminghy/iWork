package dev.agentdesktop.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class SkillInstallRepository {
    private final LocalDatabase database;

    public SkillInstallRepository(LocalDatabase database) {
        this.database = Objects.requireNonNull(database);
    }

    public Optional<ManagedInstall> findByName(String skillName) {
        String sql = "SELECT id,skill_name,managed_by_app,current_revision_id,backup_revision_id,created_at,updated_at FROM skill_managed_install WHERE skill_name=?";
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, skillName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(managed(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot query managed Skill installation", exception);
        }
    }

    public Optional<Revision> findRevision(String revisionId) {
        if (revisionId == null) return Optional.empty();
        String sql = "SELECT id,installation_id,revision_state,source_type,repository_url,requested_ref,resolved_commit_sha,source_subdirectory,package_hash,skill_content_hash,backup_key,installed_at FROM skill_install_revision WHERE id=?";
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, revisionId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(revision(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot query Skill revision", exception);
        }
    }

    public void beginTransaction(String id, String skillName, String operation,
                                 String expectedPackageHash, String previousPackageHash, Instant now) {
        execute("INSERT INTO skill_install_transaction(id,skill_name,operation,state,expected_package_hash,previous_package_hash,last_error_code,created_at,updated_at) VALUES(?,?,?,'PREPARING',?,?,NULL,?,?)",
                id, skillName, operation, expectedPackageHash, previousPackageHash, now.toString(), now.toString());
    }

    public void updateTransaction(String id, String state, String errorCode, Instant now) {
        execute("UPDATE skill_install_transaction SET state=?,last_error_code=?,updated_at=? WHERE id=?",
                state, errorCode, now.toString(), id);
    }

    public List<InstallTransaction> unfinishedTransactions() {
        String sql = "SELECT id,skill_name,operation,state,expected_package_hash,previous_package_hash,last_error_code,created_at,updated_at FROM skill_install_transaction WHERE state NOT IN ('COMMITTED','ROLLED_BACK','FAILED') ORDER BY created_at";
        List<InstallTransaction> items = new ArrayList<>();
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) items.add(transaction(result));
            return List.copyOf(items);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot query Skill transactions", exception);
        }
    }

    public void commitInstall(String installationId, String skillName, Revision active,
                              Revision backup, Instant now) {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                String existingCreated = now.toString();
                try (PreparedStatement query = connection.prepareStatement("SELECT created_at FROM skill_managed_install WHERE skill_name=?")) {
                    query.setString(1, skillName);
                    try (ResultSet result = query.executeQuery()) { if (result.next()) existingCreated = result.getString(1); }
                }
                execute(connection, "INSERT INTO skill_managed_install(id,skill_name,scope,managed_by_app,current_revision_id,backup_revision_id,created_at,updated_at) VALUES(?,?,'USER',1,?,?,?,?) ON CONFLICT(skill_name) DO UPDATE SET managed_by_app=1,current_revision_id=excluded.current_revision_id,backup_revision_id=excluded.backup_revision_id,updated_at=excluded.updated_at",
                        installationId, skillName, active.id(), backup == null ? null : backup.id(), existingCreated, now.toString());
                if (backup != null) insertRevision(connection, backup);
                insertRevision(connection, active);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot commit managed Skill installation", exception);
        }
    }

    public void markRemoved(String installationId, String revisionId, Instant now) {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "UPDATE skill_install_revision SET revision_state='REMOVED' WHERE id=? AND installation_id=?", revisionId, installationId);
                execute(connection, "UPDATE skill_managed_install SET current_revision_id=NULL,updated_at=? WHERE id=?", now.toString(), installationId);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot remove managed Skill installation", exception);
        }
    }

    public void commitRollback(String installationId, String newCurrentId, String previousCurrentId,
                               String previousBackupKey, Instant now) {
        try (Connection connection = database.open()) {
            connection.setAutoCommit(false);
            try {
                execute(connection, "UPDATE skill_install_revision SET revision_state='ACTIVE',backup_key=NULL WHERE id=? AND installation_id=?", newCurrentId, installationId);
                execute(connection, "UPDATE skill_install_revision SET revision_state='BACKUP',backup_key=? WHERE id=? AND installation_id=?",
                        previousBackupKey, previousCurrentId, installationId);
                execute(connection, "UPDATE skill_managed_install SET current_revision_id=?,backup_revision_id=?,updated_at=? WHERE id=?",
                        newCurrentId, previousCurrentId, now.toString(), installationId);
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot commit Skill rollback", exception);
        }
    }

    private void insertRevision(Connection connection, Revision revision) throws SQLException {
        execute(connection, "INSERT INTO skill_install_revision(id,installation_id,revision_state,source_type,repository_url,requested_ref,resolved_commit_sha,source_subdirectory,package_hash,skill_content_hash,backup_key,installed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET revision_state=excluded.revision_state,backup_key=excluded.backup_key",
                revision.id(), revision.installationId(), revision.state(), revision.sourceType(), revision.repositoryUrl(),
                revision.requestedRef(), revision.resolvedCommitSha(), revision.sourceSubdirectory(), revision.packageHash(),
                revision.skillContentHash(), revision.backupKey(), revision.installedAt().toString());
    }

    private void execute(String sql, Object... values) {
        try (Connection connection = database.open()) {
            execute(connection, sql, values);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot update Skill installation", exception);
        }
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }

    private static ManagedInstall managed(ResultSet result) throws SQLException {
        return new ManagedInstall(result.getString("id"), result.getString("skill_name"), result.getInt("managed_by_app") == 1,
                result.getString("current_revision_id"), result.getString("backup_revision_id"),
                Instant.parse(result.getString("created_at")), Instant.parse(result.getString("updated_at")));
    }

    private static Revision revision(ResultSet result) throws SQLException {
        return new Revision(result.getString("id"), result.getString("installation_id"), result.getString("revision_state"),
                result.getString("source_type"), result.getString("repository_url"), result.getString("requested_ref"),
                result.getString("resolved_commit_sha"), result.getString("source_subdirectory"), result.getString("package_hash"),
                result.getString("skill_content_hash"), result.getString("backup_key"), Instant.parse(result.getString("installed_at")));
    }

    private static InstallTransaction transaction(ResultSet result) throws SQLException {
        return new InstallTransaction(result.getString("id"), result.getString("skill_name"), result.getString("operation"),
                result.getString("state"), result.getString("expected_package_hash"), result.getString("previous_package_hash"),
                result.getString("last_error_code"), Instant.parse(result.getString("created_at")), Instant.parse(result.getString("updated_at")));
    }

    public record ManagedInstall(String id, String skillName, boolean managedByApp, String currentRevisionId,
                                 String backupRevisionId, Instant createdAt, Instant updatedAt) {}
    public record Revision(String id, String installationId, String state, String sourceType,
                           String repositoryUrl, String requestedRef, String resolvedCommitSha,
                           String sourceSubdirectory, String packageHash, String skillContentHash,
                           String backupKey, Instant installedAt) {}
    public record InstallTransaction(String id, String skillName, String operation, String state,
                                     String expectedPackageHash, String previousPackageHash,
                                     String lastErrorCode, Instant createdAt, Instant updatedAt) {}
}
