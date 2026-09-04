package dev.agentdesktop.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.mcp.SdkMcpClientManager;
import dev.agentdesktop.persistence.LocalDatabase;
import dev.agentdesktop.persistence.McpCatalogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitialMcpCatalogSeederTest {
    @Test void seedsOnceWithoutOverwritingOrResurrectingUserState(@TempDir Path root) throws Exception {
        Path install=Files.createDirectories(root.resolve("install"));
        Path workspace=Files.createDirectories(root.resolve("workspace"));
        Files.writeString(install.resolve("initial-mcp-catalog.json"), catalog("https://example.com/mcp"));
        LocalDatabase database=new LocalDatabase(root.resolve("home"));database.migrate();
        ObjectMapper mapper=new ObjectMapper();McpCatalogRepository repository=new McpCatalogRepository(database,mapper);
        SdkMcpClientManager manager=new SdkMcpClientManager(repository,mapper,workspace,false);

        InitialMcpCatalogSeeder.seed(install,mapper,repository,manager);
        var seeded=repository.list(10).getFirst();
        assertThat(seeded.name()).isEqualTo("xquant-indicator-mcp");
        assertThat(seeded.endpoint()).isEqualTo("https://example.com/mcp");
        assertThat(seeded.credentialRef()).isNull();

        Files.writeString(install.resolve("initial-mcp-catalog.json"), catalog("https://changed.example/mcp"));
        InitialMcpCatalogSeeder.seed(install,mapper,repository,manager);
        assertThat(repository.list(10).getFirst().endpoint()).isEqualTo("https://example.com/mcp");

        repository.softDelete(seeded.id());
        InitialMcpCatalogSeeder.seed(install,mapper,repository,manager);
        assertThat(repository.list(10)).isEmpty();
    }

    @Test void rejectsCredentialOrUnknownConfiguration(@TempDir Path root) throws Exception {
        Path install=Files.createDirectories(root.resolve("install"));
        Path workspace=Files.createDirectories(root.resolve("workspace"));
        String unsafe=catalog("https://example.com/mcp").replace("\"enabled\":true", "\"enabled\":true,\"bearerToken\":\"secret\"");
        Files.writeString(install.resolve("initial-mcp-catalog.json"),unsafe);
        LocalDatabase database=new LocalDatabase(root.resolve("home"));database.migrate();
        ObjectMapper mapper=new ObjectMapper();McpCatalogRepository repository=new McpCatalogRepository(database,mapper);
        SdkMcpClientManager manager=new SdkMcpClientManager(repository,mapper,workspace,false);
        assertThatThrownBy(()->InitialMcpCatalogSeeder.seed(install,mapper,repository,manager))
                .isInstanceOf(InitialMcpCatalogSeeder.InitialCatalogException.class)
                .hasMessage("INITIAL_MCP_CATALOG_INVALID");
    }

    private static String catalog(String endpoint) {
        return """
                {"schemaVersion":1,"servers":[{"id":"mcp_builtin-xquant-indicator-v1",
                "name":"xquant-indicator-mcp","transport":"STREAMABLE_HTTP","endpoint":"%s","enabled":true,
                "reconnectPolicy":{"maxAttempts":3,"initialBackoffMs":250,"maxBackoffMs":2000}}]}
                """.formatted(endpoint);
    }
}
