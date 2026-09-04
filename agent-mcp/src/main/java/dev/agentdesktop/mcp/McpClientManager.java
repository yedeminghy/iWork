package dev.agentdesktop.mcp;

import dev.agentdesktop.mcp.McpModels.*;
import java.util.List;

public interface McpClientManager extends AutoCloseable {
    McpServerSummary save(McpServerDraft draft);
    McpServerSummary connect(String serverId,EphemeralCredential credential);
    void disconnect(String serverId);
    void delete(String serverId);
    McpServerPage list(int limit,String cursor);
    List<ToolDescriptor> listTools(String serverId);
    ToolCallResult call(ToolInvocation invocation,CancellationToken cancellationToken);
    McpDiagnostic recentDiagnostic(String serverId);
    default OperationalStats operationalStats(){return new OperationalStats(0,0,0);}

    record OperationalStats(int activeConnections,int activeStdioProcesses,int stderrBuffers) {
        public OperationalStats {
            if(activeConnections<0||activeStdioProcesses<0||stderrBuffers<0)throw new IllegalArgumentException("Operational counters cannot be negative");
            if(activeStdioProcesses>activeConnections)throw new IllegalArgumentException("STDIO process count cannot exceed active connections");
        }
    }
}
