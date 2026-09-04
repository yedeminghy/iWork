package dev.agentdesktop.host;

import dev.agentdesktop.persistence.ConversationStore;

public record StartupRecovery(int interruptedTurns, int cancelledTurns) {
    static StartupRecovery from(ConversationStore.RecoveryReport report) {
        return new StartupRecovery(report.interrupted(), report.cancelled());
    }
}
