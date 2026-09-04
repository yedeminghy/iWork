package dev.agentdesktop.persistence;

import org.flywaydb.core.Flyway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class LocalDatabase {
    private final String jdbcUrl;
    private final Path databasePath;

    public LocalDatabase(Path appHome) {
        Objects.requireNonNull(appHome, "appHome");
        Path dataDirectory = appHome.toAbsolutePath().normalize().resolve("data");
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create application data directory", exception);
        }
        this.databasePath = dataDirectory.resolve("agent.db");
        this.jdbcUrl = "jdbc:sqlite:" + databasePath;
    }

    public void migrate() {
        migrateWith(Flyway.configure().dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration").load());
    }

    void migrateTo(int targetSchema) {
        if (targetSchema < 1) throw new IllegalArgumentException("targetSchema");
        migrateWith(Flyway.configure().dataSource(jdbcUrl, null, null)
                .locations("classpath:db/migration").target(Integer.toString(targetSchema)).load());
    }

    private void migrateWith(Flyway flyway) {
        flyway.migrate();
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = WAL");
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot configure SQLite database", exception);
        }
    }

    public Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    public DiagnosticStats diagnosticStats() {
        try (Connection connection=open()) {
            String integrity;
            try(Statement statement=connection.createStatement();ResultSet result=statement.executeQuery("PRAGMA integrity_check")){integrity=result.next()?result.getString(1):"unknown";}
            int schemaVersion=scalar(connection,"SELECT COALESCE(MAX(CAST(version AS INTEGER)),0) FROM flyway_schema_history WHERE success=1");
            Map<String,Integer> terminal=new LinkedHashMap<>();
            try(PreparedStatement statement=connection.prepareStatement("SELECT status,COUNT(*) FROM turn GROUP BY status ORDER BY status");ResultSet result=statement.executeQuery()){while(result.next())terminal.put(result.getString(1),result.getInt(2));}
            return new DiagnosticStats(schemaVersion,integrity,scalar(connection,"SELECT COUNT(*) FROM thread"),scalar(connection,"SELECT COUNT(*) FROM turn"),scalar(connection,"SELECT COUNT(*) FROM item"),Map.copyOf(terminal));
        } catch(SQLException exception){throw new IllegalStateException("Cannot inspect local database",exception);}
    }

    private static int scalar(Connection connection,String sql)throws SQLException{try(Statement statement=connection.createStatement();ResultSet result=statement.executeQuery(sql)){return result.next()?result.getInt(1):0;}}

    public record DiagnosticStats(int schemaVersion,String integrity,int threadCount,int turnCount,int itemCount,Map<String,Integer> turnStatusCounts){}

    Path databasePath() {
        return databasePath;
    }

    String jdbcUrl() {
        return jdbcUrl;
    }
}
