package dev.agentdesktop.host.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class BootstrapReader {
    private final ObjectMapper objectMapper;

    public BootstrapReader(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
    }

    public BootstrapConfig read(Reader input, Duration timeout) {
        CompletableFuture<String> line = CompletableFuture.supplyAsync(() -> {
            try {
                return new BufferedReader(input).readLine();
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read bootstrap input", exception);
            }
        });
        try {
            return parse(line.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (Exception exception) {
            line.cancel(true);
            throw new IllegalArgumentException("A valid bootstrap message was not received in time", exception);
        }
    }

    public BootstrapConfig parse(String line) {
        if (line == null || line.isBlank()) throw new IllegalArgumentException("Bootstrap message is empty");
        try {
            JsonNode node = objectMapper.readTree(line);
            if (!"bootstrap".equals(node.path("type").asText())) throw new IllegalArgumentException("Invalid bootstrap type");
            int protocol = node.path("protocolVersion").asInt(-1);
            if (protocol != BootstrapConfig.SUPPORTED_PROTOCOL) throw new IllegalArgumentException("Unsupported protocol version");
            String token = node.path("sessionToken").asText();
            if (!token.matches("[A-Za-z0-9_-]{43,128}")) throw new IllegalArgumentException("Invalid session token");
            Path appHome = Path.of(node.path("appHome").asText()).toAbsolutePath().normalize();
            return new BootstrapConfig(protocol, token, appHome);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Malformed bootstrap JSON", exception);
        }
    }
}

