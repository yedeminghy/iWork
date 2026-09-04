package dev.agentdesktop.host.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.agentdesktop.runtime.skill.importing.SkillImportException;
import dev.agentdesktop.runtime.skill.importing.SkillImportModels.GitHubSkillSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class GitHubSourceClientTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    @TempDir Path temp;

    @Test
    void pinsRefAndFetchesOnlyContentsApiIntoStaging() throws Exception {
        String document = "---\nname: github-skill\ndescription: imported from a fixed commit\n---\nBODY\n";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/repos/acme/repo")) json(exchange, 200, "{\"default_branch\":\"main\"}");
            else if (path.equals("/repos/acme/repo/commits/main")) json(exchange, 200, "{\"sha\":\"" + SHA + "\"}");
            else if (path.equals("/repos/acme/repo/contents/skills/github-skill")) {
                json(exchange, 200, "[{\"type\":\"file\",\"path\":\"skills/github-skill/SKILL.md\",\"name\":\"SKILL.md\",\"size\":" + document.getBytes(StandardCharsets.UTF_8).length + "}]");
            } else if (path.equals("/repos/acme/repo/contents/skills/github-skill/SKILL.md")) {
                json(exchange, 200, "{\"type\":\"file\",\"path\":\"skills/github-skill/SKILL.md\",\"encoding\":\"base64\",\"content\":\"" + Base64.getEncoder().encodeToString(document.getBytes(StandardCharsets.UTF_8)) + "\"}");
            } else json(exchange, 404, "{}");
        });
        server.start();
        try {
            URI api = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            GitHubSourceClient client = new GitHubSourceClient(new ObjectMapper(), HttpClient.newHttpClient(), api);
            var fetched = client.fetch(new GitHubSkillSource("https://github.com/acme/repo", null,
                    "skills/github-skill"), temp.resolve("staging"));
            assertEquals(SHA, fetched.source().resolvedCommitSha());
            assertNull(fetched.source().requestedRef());
            assertEquals("https://github.com/acme/repo", fetched.source().repositoryUrl());
            assertEquals(document, Files.readString(temp.resolve("staging/github-skill/SKILL.md")));
        } finally { server.stop(0); }
    }

    @Test
    void rejectsUntrustedRepositoryUrlBeforeNetwork() {
        GitHubSourceClient client = new GitHubSourceClient(new ObjectMapper(), HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1"));
        SkillImportException exception = assertThrows(SkillImportException.class, () -> client.fetch(
                new GitHubSkillSource("https://github.com.evil.example/acme/repo", "main", null), temp.resolve("staging")));
        assertEquals("SKILL_IMPORT_SOURCE_INVALID", exception.code());
    }

    private static void json(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
