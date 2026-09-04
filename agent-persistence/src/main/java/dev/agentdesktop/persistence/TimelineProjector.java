package dev.agentdesktop.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Maps persisted, user-visible Items to safe renderer DTOs. */
public final class TimelineProjector {
    private final ObjectMapper objectMapper;

    public TimelineProjector(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public List<TimelineEntry> project(List<ConversationRepository.ItemRecord> items) {
        Map<String, MutableEntry> entries = new LinkedHashMap<>();
        for (ConversationRepository.ItemRecord item : items) {
            if (!"USER".equals(item.visibility())) continue;
            try {
                Map<String, Object> payload = objectMapper.readValue(item.payloadJson(), new TypeReference<>() {});
                String eventType = string(payload.get("type"));
                @SuppressWarnings("unchecked") Map<String, Object> value = payload.get("value") instanceof Map<?, ?> map
                        ? (Map<String, Object>) map : payload;
                if ("USER_MESSAGE".equals(item.type())) {
                    entries.put(item.id(), new MutableEntry(item.id(), item.turnId(), "USER_MESSAGE",
                            Map.of("content", string(value.get("content")), "status", "COMPLETE")));
                } else if ("TEXT_MESSAGE_START".equals(eventType)) {
                    String id = string(value.get("messageId"));
                    entries.putIfAbsent(id, new MutableEntry(id, item.turnId(), "ASSISTANT_MESSAGE",
                            new LinkedHashMap<>(Map.of("content", "", "status", "STREAMING"))));
                } else if ("TEXT_MESSAGE_CONTENT".equals(eventType)) {
                    MutableEntry entry = entries.get(string(value.get("messageId")));
                    if (entry != null) entry.value.put("content", string(entry.value.get("content")) + string(value.get("delta")));
                } else if ("TEXT_MESSAGE_END".equals(eventType)) {
                    MutableEntry entry = entries.get(string(value.get("messageId")));
                    if (entry != null) entry.value.put("status", "COMPLETE");
                } else if ("TOOL_CALL_START".equals(eventType)) {
                    String id = string(value.get("toolCallId"));
                    Map<String,Object> card=new LinkedHashMap<>();card.put("toolId",string(value.get("toolId")));card.put("serverId",string(value.get("serverId")));
                    card.put("toolName",string(value.get("toolName")));card.put("argumentsSummary","");card.put("status","RUNNING");
                    entries.putIfAbsent(id, new MutableEntry(id, item.turnId(), "TOOL_CALL", card));
                } else if ("TOOL_CALL_ARGS".equals(eventType)) {
                    MutableEntry entry = entries.get(string(value.get("toolCallId")));
                    if (entry != null) entry.value.put("argumentsSummary", string(entry.value.get("argumentsSummary")) + string(value.get("delta")));
                } else if ("TOOL_CALL_RESULT".equals(eventType)) {
                    MutableEntry entry = entries.get(string(value.get("toolCallId")));
                    if (entry == null) {
                        String id = string(value.get("toolCallId"));
                        entry = new MutableEntry(id, item.turnId(), "TOOL_CALL", new LinkedHashMap<>());
                        entries.put(id, entry);
                    }
                    entry.value.put("resultSummary", string(value.get("resultSummary")));
                    entry.value.put("status", string(value.getOrDefault("status", "SUCCEEDED")));
                    if(value.get("elapsedMs")!=null)entry.value.put("elapsedMs",value.get("elapsedMs"));
                    if(value.get("errorCode")!=null)entry.value.put("errorCode",string(value.get("errorCode")));
                    if(value.get("artifacts") instanceof List<?>)entry.value.put("artifacts",value.get("artifacts"));
                } else if ("CUSTOM skill.activated".equals(eventType)) {
                    entries.put(item.id(),new MutableEntry(item.id(),item.turnId(),"SKILL_ACTIVATION",Map.of(
                            "skillId",string(value.get("skillId")),"name",string(value.get("name")),"scope",string(value.get("scope")),"reason",string(value.get("reason")),"contentHash",string(value.get("contentHash")))));
                } else if ("CUSTOM approval.required".equals(eventType)) {
                    String id=string(value.get("approvalId"));
                    entries.putIfAbsent(id,new MutableEntry(id,item.turnId(),"APPROVAL",new LinkedHashMap<>(value)));
                } else if ("CUSTOM approval.resolved".equals(eventType)) {
                    String id=string(value.get("approvalId"));
                    MutableEntry entry=entries.get(id);
                    if(entry==null){entry=new MutableEntry(id,item.turnId(),"APPROVAL",new LinkedHashMap<>());entries.put(id,entry);}
                    entry.value.putAll(value);
                } else if ("RUN_ERROR".equals(eventType) || "ERROR".equals(item.type())) {
                    entries.put(item.id(), new MutableEntry(item.id(), item.turnId(), "ERROR", Map.of(
                            "code", string(value.get("code")), "message", safeError(value.get("message")))));
                }
            } catch (Exception ignored) {
                entries.put(item.id(), new MutableEntry(item.id(), item.turnId(), "CORRUPTED_ITEM",
                        Map.of("status", "COMPLETE", "message", "A saved timeline entry could not be restored.")));
            }
        }
        return entries.values().stream().map(entry -> new TimelineEntry(entry.id, entry.turnId, entry.kind, Map.copyOf(entry.value))).toList();
    }

    private static String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private static String safeError(Object value) { return string(value).isBlank() ? "Agent run failed" : string(value); }
    private static final class MutableEntry {
        private final String id; private final String turnId; private final String kind; private final Map<String, Object> value;
        private MutableEntry(String id, String turnId, String kind, Map<String, Object> value) {
            this.id = id; this.turnId = turnId; this.kind = kind; this.value = new LinkedHashMap<>(value);
        }
    }
}
