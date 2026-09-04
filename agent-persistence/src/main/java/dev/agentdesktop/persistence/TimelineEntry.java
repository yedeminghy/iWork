package dev.agentdesktop.persistence;

import java.util.Map;

public record TimelineEntry(String id, String turnId, String kind, Map<String, Object> value) {}
