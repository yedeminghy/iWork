package dev.agentdesktop.runtime.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.runtime.provider.ProviderModels.ModelProfile;
import dev.agentdesktop.runtime.provider.ProviderModels.ProviderAdapter;
import dev.agentdesktop.runtime.provider.ProviderModels.ProviderCapability;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads the application-owned, fixed-endpoint provider catalog. */
public final class ProviderCatalog {
    private static final Set<String> PROVIDERS = Set.of("deepseek", "qwen");
    private static final Set<String> FIELDS = Set.of("id", "providerId", "displayName", "adapter", "modelId", "region", "capabilities");
    private final List<ModelProfile> profiles;
    private final Map<String, ModelProfile> byId;
    private final String version;

    private ProviderCatalog(List<ModelProfile> profiles, String version) {
        this.profiles = List.copyOf(profiles);
        LinkedHashMap<String, ModelProfile> values = new LinkedHashMap<>();
        for (ModelProfile profile : profiles) {
            if (values.putIfAbsent(profile.id(), profile) != null) throw new ProviderException("PROVIDER_CATALOG_INVALID");
        }
        this.byId = Map.copyOf(values);
        this.version = version;
    }

    public static ProviderCatalog load(Path path, ObjectMapper mapper) {
        try {
            byte[] bytes = Files.readAllBytes(path.toAbsolutePath().normalize());
            JsonNode root = mapper.readTree(bytes);
            if (!root.isObject() || root.path("schemaVersion").asInt(-1) != 1 || !root.path("profiles").isArray()) {
                throw new ProviderException("PROVIDER_CATALOG_INVALID");
            }
            String version = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            List<ModelProfile> profiles = new ArrayList<>();
            for (JsonNode item : root.path("profiles")) profiles.add(parse(item, version));
            if (profiles.size() != 2 || profiles.stream().map(ModelProfile::providerId).collect(java.util.stream.Collectors.toSet()).size() != 2) {
                throw new ProviderException("PROVIDER_CATALOG_INVALID");
            }
            return new ProviderCatalog(profiles, version);
        } catch (ProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ProviderException("PROVIDER_CATALOG_INVALID", exception);
        }
    }

    private static ModelProfile parse(JsonNode item, String version) throws IOException {
        if (!item.isObject()) throw new ProviderException("PROVIDER_CATALOG_INVALID");
        var names = new java.util.HashSet<String>();
        item.fieldNames().forEachRemaining(names::add);
        if (!FIELDS.equals(names)) throw new ProviderException("PROVIDER_CATALOG_INVALID");
        String id = required(item, "id", "^[a-z0-9][a-z0-9.-]{1,63}$");
        String providerId = required(item, "providerId", "^[a-z]{2,32}$");
        String displayName = required(item, "displayName", "^[^\\p{Cntrl}]{1,80}$");
        String modelId = required(item, "modelId", "^[A-Za-z0-9][A-Za-z0-9._-]{1,127}$");
        String region = required(item, "region", "^[a-z][a-z-]{1,31}$");
        if (!PROVIDERS.contains(providerId) || id.equals("mock") || id.contains("://")) throw new ProviderException("PROVIDER_CATALOG_INVALID");
        ProviderAdapter adapter;
        try { adapter = ProviderAdapter.valueOf(item.path("adapter").textValue()); }
        catch (RuntimeException exception) { throw new ProviderException("PROVIDER_CATALOG_INVALID"); }
        if ((providerId.equals("deepseek") && adapter != ProviderAdapter.OPENAI_COMPATIBLE)
                || (providerId.equals("qwen") && adapter != ProviderAdapter.DASHSCOPE)) throw new ProviderException("PROVIDER_CATALOG_INVALID");
        if (!item.path("capabilities").isArray()) throw new ProviderException("PROVIDER_CATALOG_INVALID");
        var capabilities = java.util.EnumSet.noneOf(ProviderCapability.class);
        for (JsonNode capability : item.path("capabilities")) {
            try { capabilities.add(ProviderCapability.valueOf(capability.textValue())); }
            catch (RuntimeException exception) { throw new ProviderException("PROVIDER_CATALOG_INVALID"); }
        }
        if (!capabilities.containsAll(Set.of(ProviderCapability.STREAM_TEXT, ProviderCapability.STRUCTURED_TOOL_CALL, ProviderCapability.CANCEL))) {
            throw new ProviderException("PROVIDER_CATALOG_INVALID");
        }
        return new ModelProfile(id, providerId, displayName, modelId, adapter, region, capabilities, version);
    }

    private static String required(JsonNode item, String field, String pattern) {
        String value = item.path(field).isTextual() ? item.path(field).textValue() : null;
        if (value == null || !value.matches(pattern)) throw new ProviderException("PROVIDER_CATALOG_INVALID");
        return value;
    }

    public List<ModelProfile> profiles() { return profiles; }
    public String version() { return version; }
    public ModelProfile require(String profileId) {
        ModelProfile profile = byId.get(profileId);
        if (profile == null) throw new ProviderException("PROFILE_NOT_AVAILABLE");
        return profile;
    }
}
