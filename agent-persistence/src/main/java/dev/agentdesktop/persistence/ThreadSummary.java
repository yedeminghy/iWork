package dev.agentdesktop.persistence;

public record ThreadSummary(String id, String title, String status, String modelProfileId,
                            String updatedAt, String lastTurnStatus, String preview) {}
