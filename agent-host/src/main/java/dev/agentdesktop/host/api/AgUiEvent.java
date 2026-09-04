package dev.agentdesktop.host.api;

import java.util.Map;

public record AgUiEvent(String type, String runId, String threadId, String turnId,
                        long sequence, String timestamp, Map<String, Object> value) {}
