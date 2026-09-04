package dev.agentdesktop.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseUpgradeCoordinatorTest {
    @TempDir Path home;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void migratesFreshDatabaseWithoutCreatingUpgradeBackup() throws Exception {
        DatabaseUpgradeCoordinator coordinator = new DatabaseUpgradeCoordinator(home, "0.1.0", 8, mapper);
        coordinator.prepareAndMigrate();
        assertThat(home.resolve("backups")).doesNotExist();
        assertThat(home.resolve("data/release-state.json")).doesNotExist();

        coordinator.markStartupSuccessful();
        JsonNode state = mapper.readTree(home.resolve("data/release-state.json").toFile());
        assertThat(state.path("lastSuccessfulVersion").asText()).isEqualTo("0.1.0");
        assertThat(state.path("lastSuccessfulSchema").asInt()).isEqualTo(8);
    }

    @Test
    void createsConsistentBackupBeforeForwardMigration() throws Exception {
        LocalDatabase database = new LocalDatabase(home);
        Flyway.configure().dataSource(database.jdbcUrl(), null, null).locations("classpath:db/migration").target("6").load().migrate();
        Files.writeString(home.resolve("data/release-state.json"), "{\"lastSuccessfulVersion\":\"0.0.9\"}");

        DatabaseUpgradeCoordinator coordinator = new DatabaseUpgradeCoordinator(home, "0.1.0", 8, mapper);
        coordinator.prepareAndMigrate();
        Path backupDirectory;
        try (var backups = Files.list(home.resolve("backups"))) { backupDirectory = backups.findFirst().orElseThrow(); }
        JsonNode manifest = mapper.readTree(backupDirectory.resolve("backup-manifest.json").toFile());
        assertThat(manifest.path("fromAppVersion").asText()).isEqualTo("0.0.9");
        assertThat(manifest.path("fromSchema").asInt()).isEqualTo(6);
        assertThat(manifest.path("toSchema").asInt()).isEqualTo(8);
        assertThat(manifest.path("databaseHash").asText()).matches("sha256:[0-9a-f]{64}");
        assertThat(manifest.path("size").asLong()).isEqualTo(Files.size(backupDirectory.resolve("agent.db")));
        assertThat(coordinator.readSchemaVersion(database)).isEqualTo(8);
    }

    @Test
    void refusesDatabaseNewerThanSupportedSchema() {
        LocalDatabase database = new LocalDatabase(home);
        database.migrate();
        DatabaseUpgradeCoordinator coordinator = new DatabaseUpgradeCoordinator(home, "0.0.9", 6, mapper);
        assertThatThrownBy(coordinator::prepareAndMigrate)
                .isInstanceOf(DatabaseUpgradeException.class)
                .hasMessage("DATABASE_TOO_NEW");
    }

    @Test
    void upgradesEveryPublishedPreviousSchemaWithAVerifiableBackup() throws Exception {
        for (int schema = 1; schema < 8; schema++) {
            Path versionHome = home.resolve("schema-" + schema);
            LocalDatabase database = new LocalDatabase(versionHome);
            database.migrateTo(schema);
            Files.writeString(versionHome.resolve("data/release-state.json"),
                    "{\"lastSuccessfulVersion\":\"0.0." + schema + "\"}");

            DatabaseUpgradeCoordinator coordinator = new DatabaseUpgradeCoordinator(versionHome, "0.1.0", 8, mapper);
            coordinator.prepareAndMigrate();
            coordinator.markStartupSuccessful();

            assertThat(coordinator.readSchemaVersion(database)).isEqualTo(8);
            try (var backups = Files.list(versionHome.resolve("backups"))) {
                Path backupDirectory = backups.findFirst().orElseThrow();
                JsonNode manifest = mapper.readTree(backupDirectory.resolve("backup-manifest.json").toFile());
                assertThat(manifest.path("fromSchema").asInt()).isEqualTo(schema);
                assertThat(manifest.path("toSchema").asInt()).isEqualTo(8);
                assertThat(manifest.path("databaseHash").asText()).matches("sha256:[0-9a-f]{64}");
            }
        }
    }

    @Test
    void preservesBackupAndPreviousReleaseStateWhenMigrationCannotReachTarget() throws Exception {
        LocalDatabase database = new LocalDatabase(home);
        database.migrateTo(6);
        Path state = home.resolve("data/release-state.json");
        String previousState = "{\"lastSuccessfulVersion\":\"0.0.6\",\"lastSuccessfulSchema\":6}";
        Files.writeString(state, previousState);

        DatabaseUpgradeCoordinator coordinator = new DatabaseUpgradeCoordinator(home, "0.2.0", 9, mapper);
        assertThatThrownBy(coordinator::prepareAndMigrate)
                .isInstanceOf(DatabaseUpgradeException.class)
                .hasMessage("DATABASE_MIGRATION_FAILED");
        assertThat(Files.readString(state)).isEqualTo(previousState);
        try (var backups = Files.list(home.resolve("backups"))) {
            Path backup = backups.findFirst().orElseThrow();
            assertThat(backup.resolve("agent.db")).isRegularFile();
            assertThat(backup.resolve("backup-manifest.json")).isRegularFile();
        }
    }

    @Test
    void mapsBackupRootFailureToStableCodeWithoutMigrating() throws Exception {
        LocalDatabase database = new LocalDatabase(home);
        database.migrateTo(6);
        Files.writeString(home.resolve("backups"), "not-a-directory");

        DatabaseUpgradeCoordinator coordinator = new DatabaseUpgradeCoordinator(home, "0.1.0", 8, mapper);
        assertThatThrownBy(coordinator::prepareAndMigrate)
                .isInstanceOf(DatabaseUpgradeException.class)
                .hasMessage("DATABASE_BACKUP_FAILED");
        assertThat(coordinator.readSchemaVersion(database)).isEqualTo(6);
    }

    @Test
    void releaseStateFailureDoesNotReportStartupSuccess() throws Exception {
        DatabaseUpgradeCoordinator coordinator = new DatabaseUpgradeCoordinator(home, "0.1.0", 8, mapper);
        coordinator.prepareAndMigrate();
        Files.createDirectory(home.resolve("data/release-state.json"));

        assertThatThrownBy(coordinator::markStartupSuccessful)
                .isInstanceOf(DatabaseUpgradeException.class)
                .hasMessage("DATABASE_MIGRATION_FAILED");
    }

    @Test
    void retainsOnlyThreeOwnedSuccessfulUpgradeBackups() throws Exception {
        LocalDatabase database = new LocalDatabase(home);
        database.migrateTo(6);
        Path backups = Files.createDirectories(home.resolve("backups"));
        Instant base = Instant.now().minus(10, ChronoUnit.DAYS);
        for (int index = 0; index < 4; index++) {
            Path directory = Files.createDirectory(backups.resolve("old-" + index));
            Files.writeString(directory.resolve("agent.db"), "fixture-" + index);
            Path manifest = directory.resolve("backup-manifest.json");
            Files.writeString(manifest, "{}");
            Files.setLastModifiedTime(manifest, FileTime.from(base.plus(index, ChronoUnit.DAYS)));
        }

        DatabaseUpgradeCoordinator coordinator = new DatabaseUpgradeCoordinator(home, "0.1.0", 8, mapper);
        coordinator.prepareAndMigrate();
        coordinator.markStartupSuccessful();

        try (var directories = Files.list(backups)) {
            assertThat(directories.filter(Files::isDirectory)).hasSize(3);
        }
    }
}
