package dev.agentdesktop.host.bootstrap;

import java.nio.file.Path;

public record BootstrapConfig(int protocolVersion, String sessionToken, Path appHome) {
    public static final int SUPPORTED_PROTOCOL = 1;
}

