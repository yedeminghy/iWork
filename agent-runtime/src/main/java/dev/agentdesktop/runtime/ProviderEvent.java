package dev.agentdesktop.runtime;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public sealed interface ProviderEvent permits ProviderEvent.TextDelta, ProviderEvent.ToolCallStart,
        ProviderEvent.ToolCallArgs, ProviderEvent.StructuredToolCall, ProviderEvent.StructuredSkillActivate,
        ProviderEvent.StructuredSkillReadResource, ProviderEvent.ToolCallResult,
        ProviderEvent.SkillActivated, ProviderEvent.SkillResourceLoaded,
        ProviderEvent.ApprovalRequired, ProviderEvent.ApprovalResolved, ProviderEvent.Completed {
    record TextDelta(String content) implements ProviderEvent {}
    record ToolCallStart(String toolCallId, String toolName) implements ProviderEvent {}
    record ToolCallArgs(String toolCallId, String delta) implements ProviderEvent {}
    record StructuredToolCall(String toolCallId,String toolId,JsonNode arguments) implements ProviderEvent {}
    record StructuredSkillActivate(String skillId) implements ProviderEvent {}
    record StructuredSkillReadResource(String skillId,String resourceId) implements ProviderEvent {}
    record SkillActivated(String skillId,String name,String scope,String reason,String contentHash) implements ProviderEvent {}
    record SkillResourceLoaded(String skillId,String resourceId,String kind,long byteSize,String contentHash) implements ProviderEvent {}
    record OutputArtifact(String rootId,String relativePath,String fileName,String mediaType,long byteSize) {}
    record ToolCallResult(String toolCallId, String status, String resultSummary, String errorCode, long elapsedMs,
                          List<OutputArtifact> artifacts) implements ProviderEvent {
        public ToolCallResult { artifacts=artifacts==null?List.of():List.copyOf(artifacts); }
        public ToolCallResult(String toolCallId,String status,String resultSummary){this(toolCallId,status,resultSummary,null,0,List.of());}
        public ToolCallResult(String toolCallId,String status,String resultSummary,String errorCode,long elapsedMs){this(toolCallId,status,resultSummary,errorCode,elapsedMs,List.of());}
    }
    record ApprovalRequired(long sequence, java.util.Map<String,Object> value) implements ProviderEvent {}
    record ApprovalResolved(long sequence, java.util.Map<String,Object> value) implements ProviderEvent {}
    record Completed() implements ProviderEvent {}
}
