package dev.agentdesktop.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Application-owned tool boundary exposed to Harness; implementations must route through ToolGateway. */
public interface HarnessToolBoundary {
    List<ToolDefinition> definitions();
    Mono<ToolExecution> execute(ToolCall call, Consumer<ProviderEvent> events);

    record ToolDefinition(String agentName, String toolId, String description, Map<String, Object> parameters,
                          boolean userVisible) {
        public ToolDefinition(String agentName,String toolId,String description,Map<String,Object> parameters) {
            this(agentName,toolId,description,parameters,true);
        }
    }
    record ToolCall(String callId, String agentName, String toolId, JsonNode arguments) {}
    record ToolExecution(String callId, String toolId, String status, JsonNode modelResult,
                         String userSummary, String errorCode, long elapsedMs) {}

    static HarnessToolBoundary disabled() {
        return new HarnessToolBoundary() {
            @Override public List<ToolDefinition> definitions() { return List.of(); }
            @Override public Mono<ToolExecution> execute(ToolCall call, Consumer<ProviderEvent> events) {
                return Mono.error(new IllegalStateException("TOOL_DISABLED"));
            }
        };
    }
}
