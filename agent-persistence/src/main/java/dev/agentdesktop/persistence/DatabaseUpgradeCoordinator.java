package dev.agentdesktop.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/** Coordinates forward-only SQLite upgrades before any business repository opens. */
public final class DatabaseUpgradeCoordinator {
    private static final int BACKUPS_TO_KEEP = 3;
    private final Path appHome;
    private final Path backupsRoot;
    private final Path releaseState;
    private final String appVersion;
    private final int targetSchema;
    private final ObjectMapper mapper;
    private LocalDatabase database;
    private int startingSchema;
    private boolean prepared;

    public DatabaseUpgradeCoordinator(Path appHome, String appVersion, int targetSchema, ObjectMapper mapper) {
        this.appHome = Objects.requireNonNull(appHome).toAbsolutePath().normalize();
        this.backupsRoot = this.appHome.resolve("backups").normalize();
        this.releaseState = this.appHome.resolve("data").resolve("release-state.json").normalize();
        this.appVersion = requireVersion(appVersion);
        if (targetSchema < 1) throw new IllegalArgumentException("targetSchema");
        this.targetSchema = targetSchema;
        this.mapper = Objects.requireNonNull(mapper);
    }

    public synchronized LocalDatabase prepareAndMigrate() {
        if (prepared) return database;
        database = new LocalDatabase(appHome);
        startingSchema = readSchemaVersion(database);
        if (startingSchema > targetSchema) throw new DatabaseUpgradeException("DATABASE_TOO_NEW");
        if (startingSchema > 0 && startingSchema < targetSchema) createBackup(database, startingSchema);
        try {
            database.migrateTo(targetSchema);
        } catch (RuntimeException exception) {
            throw new DatabaseUpgradeException("DATABASE_MIGRATION_FAILED", exception);
        }
        int migratedSchema = readSchemaVersion(database);
        if (migratedSchema != targetSchema) throw new DatabaseUpgradeException("DATABASE_MIGRATION_FAILED");
        prepared = true;
        return database;
    }

    /** Called only after repositories, reconciliation and the web application are ready. */
    public synchronized void markStartupSuccessful() {
        if (!prepared) throw new IllegalStateException("Database upgrade was not prepared");
        writeAtomic(releaseState, new ReleaseState(1, appVersion, targetSchema, Instant.now().toString()), "DATABASE_MIGRATION_FAILED");
        if (startingSchema > 0 && startingSchema < targetSchema) retainRecentBackups();
    }

    int readSchemaVersion(LocalDatabase localDatabase) {
        if (!Files.exists(localDatabase.databasePath())) return 0;
        try (Connection connection = localDatabase.open(); Statement statement = connection.createStatement()) {
            try (ResultSet tables = statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='flyway_schema_history'")) {
                if (!tables.next()) return 0;
            }
            try (ResultSet result = statement.executeQuery("SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")) {
                if (!result.next()) return 0;
                String version = result.getString(1);
                int separator = version.indexOf('.');
                return Integer.parseInt(separator < 0 ? version : version.substring(0, separator));
            }
        } catch (SQLException | NumberFormatException exception) {
            throw new DatabaseUpgradeException("DATABASE_MIGRATION_FAILED", exception);
        }
    }

    private void createBackup(LocalDatabase localDatabase, int fromSchema) {
        try {
            Files.createDirectories(backupsRoot);
            Path canonicalRoot = backupsRoot.toRealPath();
            String backupId = appVersion + "-" + UUID.randomUUID();
            Path directory = canonicalRoot.resolve(backupId).normalize();
            if (!directory.getParent().equals(canonicalRoot)) throw new DatabaseUpgradeException("DATABASE_BACKUP_FAILED");
            Files.createDirectory(directory);
            Path backup = directory.resolve("agent.db");
            try (Connection connection = localDatabase.open(); Statement statement = connection.createStatement()) {
                try (ResultSet integrity = statement.executeQuery("PRAGMA integrity_check")) {
                    if (!integrity.next() || !"ok".equalsIgnoreCase(integrity.getString(1))) {
                        throw new DatabaseUpgradeException("DATABASE_BACKUP_FAILED");
                    }
                }
                statement.execute("PRAGMA wal_checkpoint(FULL)");
                statement.execute("VACUUM INTO '" + backup.toString().replace("'", "''") + "'");
            }
            long size = Files.size(backup);
            if (size < 1) throw new DatabaseUpgradeException("DATABASE_BACKUP_FAILED");
            BackupManifest manifest = new BackupManifest(1, previousVersion(), appVersion, fromSchema, targetSchema,
                    sha256(backup), size, Instant.now().toString());
            writeAtomic(directory.resolve("backup-manifest.json"), manifest, "DATABASE_BACKUP_FAILED");
        } catch (DatabaseUpgradeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new DatabaseUpgradeException("DATABASE_BACKUP_FAILED", exception);
        }
    }

    private String previousVersion() {
        if (!Files.exists(releaseState)) return "unknown";
        try {
            String value = mapper.readTree(releaseState.toFile()).path("lastSuccessfulVersion").asText("unknown");
            return value.matches("[0-9A-Za-z.+-]{1,64}") ? value : "unknown";
        } catch (IOException exception) {
            return "unknown";
        }
    }

    private void retainRecentBackups() {
        try (Stream<Path> stream = Files.list(backupsRoot)) {
            List<Path> directories = stream.filter(Files::isDirectory)
                    .filter(path -> Files.isRegularFile(path.resolve("backup-manifest.json")))
                    .sorted(Comparator.comparingLong(DatabaseUpgradeCoordinator::modifiedAt).reversed()).toList();
            for (Path directory : directories.stream().skip(BACKUPS_TO_KEEP).toList()) deleteOwnedBackup(directory);
        } catch (IOException exception) {
            // Retention is best-effort after a successful start; never make the application unavailable for cleanup.
        }
    }

    private void deleteOwnedBackup(Path directory) throws IOException {
        Path root = backupsRoot.toRealPath();
        Path target = directory.toRealPath();
        if (!target.startsWith(root) || !target.getParent().equals(root)) return;
        try (Stream<Path> entries = Files.walk(target)) {
            for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        }
    }

    private void writeAtomic(Path target, Object value, String failureCode) {
        try {
            Files.createDirectories(target.getParent());
            byte[] bytes = mapper.writeValueAsBytes(value);
            Path temporary = target.resolveSibling(target.getFileName() + "." + ProcessHandle.current().pid() + ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new DatabaseUpgradeException(failureCode, exception);
            }
        } catch (DatabaseUpgradeException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new DatabaseUpgradeException(failureCode, exception);
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) >= 0; ) if (read > 0) digest.update(buffer, 0, read);
        }
        return "sha256:" + HexFormat.of().formatHex(digest.digest());
    }

    private static long modifiedAt(Path path) {
        try { return Files.getLastModifiedTime(path.resolve("backup-manifest.json")).toMillis(); }
        catch (IOException exception) { return 0; }
    }

    private static String requireVersion(String value) {
        if (value == null || !value.matches("[0-9A-Za-z.+-]{1,64}")) throw new IllegalArgumentException("appVersion");
        return value;
    }

    public record BackupManifest(int schemaVersion, String fromAppVersion, String toAppVersion,
                                 int fromSchema, int toSchema, String databaseHash, long size, String createdAt) {}
    public record ReleaseState(int schemaVersion, String lastSuccessfulVersion, int lastSuccessfulSchema, String at) {}
}
