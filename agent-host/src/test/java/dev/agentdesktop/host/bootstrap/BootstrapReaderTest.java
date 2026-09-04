package dev.agentdesktop.host.bootstrap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapReaderTest {
    private final BootstrapReader reader = new BootstrapReader(new ObjectMapper());

    @Test
    void acceptsSupportedBootstrapMessage() {
        BootstrapConfig config = reader.parse("""
                {"type":"bootstrap","protocolVersion":1,
                 "sessionToken":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                 "appHome":"build/test-home"}
                """);
        assertThat(config.protocolVersion()).isEqualTo(1);
        assertThat(config.appHome()).isAbsolute();
    }

    @Test
    void rejectsShortTokenAndWrongProtocol() {
        assertThatThrownBy(() -> reader.parse("""
                {"type":"bootstrap","protocolVersion":2,"sessionToken":"short","appHome":"."}
                """)).isInstanceOf(IllegalArgumentException.class);
    }
}

