package dev.agentdesktop.persistence;

public record TurnSummary(String id, String runId, String status, String startedAt,
                          String finishedAt, String errorCode, long lastSequence,String retryOfTurnId) {
    public TurnSummary(String id,String runId,String status,String startedAt,String finishedAt,String errorCode,long lastSequence){this(id,runId,status,startedAt,finishedAt,errorCode,lastSequence,null);}
}
