package dev.agentdesktop.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Persists only provider health metadata; credentials and remote payloads are forbidden here. */
public final class ProviderProfileRepository {
    private final LocalDatabase database;
    private final ObjectMapper mapper;

    public ProviderProfileRepository(LocalDatabase database, ObjectMapper mapper) {
        this.database = Objects.requireNonNull(database);
        this.mapper = Objects.requireNonNull(mapper);
    }

    public synchronized void ensureProfiles(List<ProfileSeed> profiles) {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO provider_profile_state(profile_id,enabled,status,observed_capabilities_json,catalog_version) VALUES(?,1,'NOT_CONFIGURED','[]',?) " +
                        "ON CONFLICT(profile_id) DO UPDATE SET catalog_version=excluded.catalog_version")) {
            for (ProfileSeed profile : profiles) {
                statement.setString(1, profile.profileId());
                statement.setString(2, profile.catalogVersion());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (Exception exception) {
            throw new PersistenceException("Cannot initialize provider profiles", exception);
        }
    }

    public synchronized void recordCheck(String profileId, String status, Set<String> capabilities,
                                         String errorCode, Instant checkedAt, String catalogVersion) {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE provider_profile_state SET status=?,observed_capabilities_json=?,last_error_code=?,last_checked_at=?,catalog_version=? WHERE profile_id=?")) {
            statement.setString(1, status);
            statement.setString(2, mapper.writeValueAsString(capabilities.stream().sorted().toList()));
            statement.setString(3, errorCode);
            statement.setString(4, checkedAt.toString());
            statement.setString(5, catalogVersion);
            statement.setString(6, profileId);
            if (statement.executeUpdate() != 1) throw new PersistenceException("Provider profile is not installed", null);
        } catch (PersistenceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PersistenceException("Cannot update provider profile", exception);
        }
    }

    public List<ProfileState> list() {
        try (Connection connection = database.open(); PreparedStatement statement = connection.prepareStatement(
                "SELECT profile_id,enabled,status,observed_capabilities_json,last_error_code,last_checked_at,catalog_version FROM provider_profile_state ORDER BY profile_id");
             ResultSet result = statement.executeQuery()) {
            List<ProfileState> values = new ArrayList<>();
            while (result.next()) values.add(new ProfileState(result.getString(1), result.getBoolean(2), result.getString(3),
                    Set.copyOf(mapper.readValue(result.getString(4), new TypeReference<List<String>>() {})),
                    result.getString(5), result.getString(6), result.getString(7)));
            return List.copyOf(values);
        } catch (Exception exception) {
            throw new PersistenceException("Cannot list provider profiles", exception);
        }
    }

    public record ProfileSeed(String profileId, String catalogVersion) {}
    public record ProfileState(String profileId, boolean enabled, String status, Set<String> observedCapabilities,
                               String lastErrorCode, String lastCheckedAt, String catalogVersion) {}
    public static final class PersistenceException extends RuntimeException {
        public PersistenceException(String message, Throwable cause) { super(message, cause); }
    }
}
