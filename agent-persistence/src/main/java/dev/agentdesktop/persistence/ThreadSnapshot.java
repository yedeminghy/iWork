package dev.agentdesktop.persistence;

import java.util.List;

public record ThreadSnapshot(int schemaVersion, ThreadSummary thread, List<TurnSummary> turns,
                             List<TimelineEntry> timeline) {}
