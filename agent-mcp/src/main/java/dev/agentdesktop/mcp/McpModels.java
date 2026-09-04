package dev.agentdesktop.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class McpModels {
    private McpModels() {}

    public sealed interface McpServerDraft permits StdioServerDraft, StreamableHttpServerDraft {
        String id(); String name(); boolean enabled(); ReconnectPolicy reconnectPolicy();
    }
    public record StdioServerDraft(String id,String name,String executable,List<String> arguments,Path workingDirectory,
                                   List<String> allowedEnvironmentNames,boolean enabled,ReconnectPolicy reconnectPolicy)
            implements McpServerDraft {}
    public record StreamableHttpServerDraft(String id,String name,URI endpoint,String credentialRef,boolean enabled,
                                            ReconnectPolicy reconnectPolicy) implements McpServerDraft {}
    public record ReconnectPolicy(int maxAttempts,Duration initialBackoff,Duration maxBackoff) {
        public static ReconnectPolicy defaults(){return new ReconnectPolicy(3,Duration.ofMillis(250),Duration.ofSeconds(2));}
    }
    public record EphemeralCredential(String bearerToken,Map<String,String> environment) {
        public static EphemeralCredential empty(){return new EphemeralCredential(null,Map.of());}
        public EphemeralCredential { environment=environment==null?Map.of():Map.copyOf(environment); }
    }
    public enum RiskClass { DEMO_SAFE,READ,NETWORK,WRITE,EXECUTE,UNKNOWN }
    public record McpServerSummary(String id,String name,String transport,String status,boolean enabled,String endpoint,
                                   String executable,List<String> arguments,String workingDirectory,List<String> allowedEnvironmentNames,
                                   String credentialRef,String lastErrorCode,String lastConnectedAt,long catalogVersion) {}
    public record McpServerPage(List<McpServerSummary> items,String nextCursor) {}
    public record ToolDescriptor(String id,String serverId,String name,String displayName,String description,
                                 JsonNode inputSchema,RiskClass riskClass,boolean enabled,String unavailableReason) {}
    public record ToolInvocation(String callId,String threadId,String turnId,String toolId,JsonNode arguments,Instant requestedAt) {}
    public enum ToolOutcome { SUCCEEDED,FAILED,CANCELLED }
    public record ToolCallResult(String callId,ToolOutcome outcome,JsonNode modelResult,String userSummary,
                                 String errorCode,Duration elapsed) {}
    public record McpDiagnostic(String serverId,String status,String errorCode,String summary,List<String> recentStderr) {}

    public interface CancellationToken { boolean isCancellationRequested(); }
}
