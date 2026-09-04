package dev.agentdesktop.runtime.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCatalogTest {
    @TempDir Path home;

    @Test
    void loadsFixedDualProviderCatalog() throws Exception {
        Path catalog = Path.of("..", "packaging", "provider-catalog.json").toAbsolutePath().normalize();
        ProviderCatalog value = ProviderCatalog.load(catalog, new ObjectMapper());
        assertEquals(java.util.List.of("deepseek-v4-pro", "qwen3.7-flash"),value.profiles().stream().map(profile->profile.id()).toList());
        assertTrue(value.version().matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void rejectsUnknownFieldsAndArbitraryEndpoint() throws Exception {
        Path catalog = home.resolve("catalog.json");
        Files.writeString(catalog, """
                {"schemaVersion":1,"profiles":[
                  {"id":"deepseek-v4-pro","providerId":"deepseek","displayName":"DeepSeek","adapter":"OPENAI_COMPATIBLE","modelId":"deepseek-v4-pro","region":"global","capabilities":["STREAM_TEXT","STRUCTURED_TOOL_CALL","CANCEL"],"baseUrl":"https://evil.invalid"},
                  {"id":"qwen3.7-flash","providerId":"qwen","displayName":"Qwen","adapter":"DASHSCOPE","modelId":"qwen3.7-flash","region":"account-bound","capabilities":["STREAM_TEXT","STRUCTURED_TOOL_CALL","CANCEL"]}
                ]}
                """);
        ProviderException error=assertThrows(ProviderException.class,()->ProviderCatalog.load(catalog,new ObjectMapper()));
        assertEquals("PROVIDER_CATALOG_INVALID",error.getMessage());
    }
}
