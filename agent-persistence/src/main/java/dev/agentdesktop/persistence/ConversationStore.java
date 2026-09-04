package dev.agentdesktop.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Transactional command and query boundary for the durable conversation timeline. */
public interface ConversationStore {
    StartTurnResult startTurn(StartTurnCommand command);
    AppendResult appendEvent(AppendEventCommand command);
    CancelResult requestCancellation(String turnId, Instant requestedAt);
    FinalizeResult finalizeTurn(FinalizeTurnCommand command);
    RecoveryReport reconcileUnfinishedTurns(Instant startupAt);
    ThreadPage listThreads(ThreadQuery query);
    ThreadSnapshot loadThreadSnapshot(String threadId);
    List<ConversationMessage> loadConversationHistory(String threadId,String excludedTurnId,int maxMessages,int maxCharacters);
    ThreadSummary renameThread(String threadId, String title);
    ThreadSummary setThreadArchived(String threadId, boolean archived);
    ThreadSummary selectThreadProfile(String threadId, String modelProfileId);
    AppendResult appendSteering(String turnId,String itemId,String message,Instant createdAt);
    RetryPreparation prepareRetry(String turnId);

    record StartTurnCommand(String threadId, String turnId, String runId, String modelProfileId,
                            String userItemId, String message, Instant startedAt,String retryOfTurnId) {
        public StartTurnCommand(String threadId,String turnId,String runId,String modelProfileId,String userItemId,String message,Instant startedAt){this(threadId,turnId,runId,modelProfileId,userItemId,message,startedAt,null);}
    }
    record StartTurnResult(ThreadSummary thread, TurnSummary turn, long userSequence) {}
    record AppendEventCommand(String eventId, String threadId, String turnId, String type,
                              String visibility, Map<String, Object> payload, Instant createdAt) {}
    record AppendResult(String itemId, long sequence, boolean replayed, Map<String, Object> payload) {}
    record FinalizeTurnCommand(String turnId, String terminalStatus, String errorCode,
                               String eventId, String itemType, String visibility,
                               Map<String, Object> payload, Instant finishedAt) {}
    record FinalizeResult(TurnSummary turn, Long sequence, boolean finalized) {}
    record CancelResult(String turnId, String status, boolean cancellationRequested, Long sequence) {}
    record RecoveryReport(int interrupted, int cancelled) {}
    record ThreadQuery(String status, int limit, String cursor) {}
    record ThreadPage(List<ThreadSummary> items, String nextCursor) {}
    record RetryPreparation(String turnId,String threadId,String message,String modelProfileId) {}
    record ConversationMessage(String role,String content) {}
}
