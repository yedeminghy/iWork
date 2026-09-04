package dev.agentdesktop.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.mcp.McpClientManager;
import dev.agentdesktop.mcp.McpModels.ReconnectPolicy;
import dev.agentdesktop.mcp.McpModels.StreamableHttpServerDraft;
import dev.agentdesktop.persistence.McpCatalogRepository;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

/** Seeds non-secret product defaults exactly once without overriding user-owned MCP state. */
final class InitialMcpCatalogSeeder {
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "servers");
    private static final Set<String> SERVER_FIELDS = Set.of(
            "id", "name", "transport", "endpoint", "enabled", "reconnectPolicy");
    private static final Set<String> POLICY_FIELDS = Set.of(
            "maxAttempts", "initialBackoffMs", "maxBackoffMs");

    private InitialMcpCatalogSeeder() {}

    static void seed(Path installDirectory, ObjectMapper mapper, McpCatalogRepository repository,
                     McpClientManager manager) {
        try {
            Path install = installDirectory.toRealPath();
            Path catalog = install.resolve("initial-mcp-catalog.json").normalize();
            if (!catalog.getParent().equals(install) || !Files.isRegularFile(catalog) || Files.isSymbolicLink(catalog)) fail();
            JsonNode root = mapper.readTree(catalog.toFile());
            requireFields(root, ROOT_FIELDS);
            if (root.path("schemaVersion").asInt(-1) != 1 || !root.path("servers").isArray()) fail();
            for (JsonNode server : root.path("servers")) seedServer(server, repository, manager);
        } catch (InitialCatalogException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InitialCatalogException(exception);
        }
    }

    private static void seedServer(JsonNode server, McpCatalogRepository repository, McpClientManager manager) {
        requireFields(server, SERVER_FIELDS);
        String id = text(server, "id");
        String name = text(server, "name");
        String transport = text(server, "transport");
        String endpoint = text(server, "endpoint");
        if (!"STREAMABLE_HTTP".equals(transport) || !server.path("enabled").isBoolean()) fail();
        JsonNode policy = server.path("reconnectPolicy");
        requireFields(policy, POLICY_FIELDS);
        int attempts = exactInt(policy, "maxAttempts");
        int initialBackoffMs = exactInt(policy, "initialBackoffMs");
        int maxBackoffMs = exactInt(policy, "maxBackoffMs");
        if (repository.hasServerNamedIncludingDeleted(name)) return;
        manager.save(new StreamableHttpServerDraft(id, name, URI.create(endpoint), null,
                server.path("enabled").booleanValue(), new ReconnectPolicy(attempts,
                Duration.ofMillis(initialBackoffMs), Duration.ofMillis(maxBackoffMs))));
    }

    private static void requireFields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) fail();
        node.fieldNames().forEachRemaining(field -> { if (!allowed.contains(field)) fail(); });
        for (String field : allowed) if (!node.has(field)) fail();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) fail();
        return value.textValue();
    }

    private static int exactInt(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) fail();
        return value.intValue();
    }

    private static void fail() { throw new InitialCatalogException(); }

    static final class InitialCatalogException extends RuntimeException {
        InitialCatalogException() { super("INITIAL_MCP_CATALOG_INVALID"); }
        InitialCatalogException(Throwable cause) { super("INITIAL_MCP_CATALOG_INVALID", cause); }
    }
}
