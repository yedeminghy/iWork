package dev.agentdesktop.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.mcp.McpClientManager;
import dev.agentdesktop.mcp.McpModels;
import dev.agentdesktop.runtime.permission.DefaultPermissionEngine;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ToolGatewayTest {
    @Test
    void recognizesPptxAsPreviewableOutputArtifact() {
        Path pptx=Path.of("report.pptx");
        assertTrue(ToolGateway.supportedArtifact(pptx));
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation",ToolGateway.mediaType(pptx));
    }

    @Test
    void exposesControlledScriptEntryAndMcpServerIdentity() {
        ObjectMapper mapper = new ObjectMapper();
        McpModels.McpServerSummary server = new McpModels.McpServerSummary("mcp_fixture123", "xquant-indicator-mcp",
                "STREAMABLE_HTTP", "READY", true, "https://example.test/mcp", null, List.of(), null,
                List.of(), null, null, null, 1);
        McpModels.ToolDescriptor tool = new McpModels.ToolDescriptor("mcp:mcp_fixture123:calculate_indicators",
                server.id(), "calculate_indicators", "calculate_indicators", "Calculate approved indicators",
                mapper.valueToTree(Map.of("type", "object", "properties", Map.of())), McpModels.RiskClass.READ, true, null);
        ToolGateway gateway = new ToolGateway(new FixtureManager(server, List.of(tool)), new DefaultPermissionEngine(false), mapper);

        List<HarnessToolBoundary.ToolDefinition> definitions = gateway.harnessDefinitions();
        assertTrue(definitions.stream().anyMatch(item -> item.toolId().equals("skill.run_script")));
        assertTrue(definitions.stream().anyMatch(item -> item.toolId().equals(tool.id())
                && item.description().contains("xquant-indicator-mcp/calculate_indicators")));
        HarnessToolBoundary.ToolDefinition write = definitions.stream()
                .filter(item -> item.toolId().equals("workspace.write_text"))
                .findFirst().orElseThrow();
        assertEquals("root_default", mapper.valueToTree(write.parameters())
                .path("properties").path("rootId").path("const").asText());
        assertTrue(write.description().contains("root_default"));
    }

    @Test
    void acceptsConnectedCatalogWithTwoHundredEightMcpTools() {
        ObjectMapper mapper = new ObjectMapper();
        McpModels.McpServerSummary server = readyServer();
        ToolGateway gateway = new ToolGateway(new FixtureManager(server, tools(server, mapper, 208)),
                new DefaultPermissionEngine(false), mapper);

        assertEquals(212, gateway.harnessDefinitions().size());
    }

    @Test
    void rejectsCatalogAboveBoundedHarnessLimit() {
        ObjectMapper mapper = new ObjectMapper();
        McpModels.McpServerSummary server = readyServer();
        ToolGateway gateway = new ToolGateway(new FixtureManager(server, tools(server, mapper, 253)),
                new DefaultPermissionEngine(false), mapper);

        dev.agentdesktop.runtime.permission.PermissionException error = assertThrows(
                dev.agentdesktop.runtime.permission.PermissionException.class,
                gateway::harnessDefinitions);
        assertEquals("TOOL_CATALOG_TOO_LARGE", error.code());
    }

    private static McpModels.McpServerSummary readyServer() {
        return new McpModels.McpServerSummary("mcp_fixture123", "investment-manager-mcp",
                "STREAMABLE_HTTP", "READY", true, "https://example.test/mcp", null, List.of(), null,
                List.of(), null, null, null, 1);
    }

    private static List<McpModels.ToolDescriptor> tools(McpModels.McpServerSummary server, ObjectMapper mapper, int count) {
        return IntStream.range(0, count).mapToObj(index -> {
            String name = "tool_" + index;
            return new McpModels.ToolDescriptor("mcp:" + server.id() + ":" + name,
                    server.id(), name, name, "Investment manager tool " + index,
                    mapper.valueToTree(Map.of("type", "object", "properties", Map.of())),
                    McpModels.RiskClass.READ, true, null);
        }).toList();
    }

    private record FixtureManager(McpModels.McpServerSummary server, List<McpModels.ToolDescriptor> tools) implements McpClientManager {
        @Override public McpModels.McpServerSummary save(McpModels.McpServerDraft draft) { throw new UnsupportedOperationException(); }
        @Override public McpModels.McpServerSummary connect(String serverId, McpModels.EphemeralCredential credential) { throw new UnsupportedOperationException(); }
        @Override public void disconnect(String serverId) {}
        @Override public void delete(String serverId) {}
        @Override public McpModels.McpServerPage list(int limit, String cursor) { return new McpModels.McpServerPage(List.of(server), null); }
        @Override public List<McpModels.ToolDescriptor> listTools(String serverId) { return tools; }
        @Override public McpModels.ToolCallResult call(McpModels.ToolInvocation invocation, McpModels.CancellationToken cancellationToken) {
            return new McpModels.ToolCallResult(invocation.callId(), McpModels.ToolOutcome.SUCCEEDED,
                    new ObjectMapper().createObjectNode(), "ok", null, Duration.ZERO);
        }
        @Override public McpModels.McpDiagnostic recentDiagnostic(String serverId) { return null; }
        @Override public void close() {}
    }
}
