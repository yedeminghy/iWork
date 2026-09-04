package dev.agentdesktop.mcp;

import dev.agentdesktop.mcp.McpModels.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpDraftValidatorTest {
    @Test void acceptsHttpAndRejectsShellOrUnsupportedEndpoint(){
        Path workspace=Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        McpDraftValidator.validate(new StreamableHttpServerDraft(null,"remote",URI.create("http://example.com/mcp"),null,true,ReconnectPolicy.defaults()),workspace,false);
        assertThatThrownBy(()->McpDraftValidator.validate(new StreamableHttpServerDraft(null,"remote",URI.create("ftp://example.com/mcp"),null,true,ReconnectPolicy.defaults()),workspace,false)).isInstanceOf(McpException.class);
        assertThatThrownBy(()->McpDraftValidator.validate(new StreamableHttpServerDraft(null,"remote",URI.create("https://user:secret@example.com/mcp"),null,true,ReconnectPolicy.defaults()),workspace,false)).isInstanceOf(McpException.class);
        assertThatThrownBy(()->McpDraftValidator.validate(new StreamableHttpServerDraft(null,"remote",URI.create("https://example.com/mcp#fragment"),null,true,ReconnectPolicy.defaults()),workspace,false)).isInstanceOf(McpException.class);
        String shell=Path.of(System.getenv().getOrDefault("SystemRoot","C:\\Windows"),"System32","cmd.exe").toString();
        assertThatThrownBy(()->McpDraftValidator.validate(new StdioServerDraft(null,"shell",shell,java.util.List.of(),workspace,java.util.List.of(),true,ReconnectPolicy.defaults()),workspace,true)).isInstanceOf(McpException.class);
    }
}
