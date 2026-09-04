package dev.agentdesktop.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Durable, transactional boundary for approvals, permission audit, and declared file roots. */
public interface PermissionStore {
    ApprovalRequested requestApproval(ApprovalDraft draft);
    ApprovalResolution resolveApproval(String approvalId, String decision, String expectedFingerprint, Instant now);
    ApprovalResolution expireApproval(String approvalId, Instant now);
    ApprovalResolution cancelPendingForTurn(String turnId, String code, Instant now);
    List<ApprovalSummary> pendingForTurn(String turnId);
    void recordPolicyEvaluation(PolicyAuditDraft draft);

    List<FileRootSummary> listRoots(String workspaceId);
    FileRootSummary addRoot(FileRootDraft draft);
    FileRootSummary updateRoot(String rootId, String mode, boolean enabled, long expectedVersion);
    void removeRoot(String rootId, long expectedVersion);
    FileRootRecord getRoot(String rootId);

    record ApprovalDraft(String id, String threadId, String turnId, String callId, String toolId,
                         String capabilityClass, String riskLevel, String requestFingerprint,
                         String subjectFingerprint, String argumentsHash, String resourceScopeHash,
                         Map<String, Object> safeRequest, Instant expiresAt, Instant createdAt) {}
    record ApprovalSummary(String id, String threadId, String turnId, String callId, String toolId,
                           String capabilityClass, String riskLevel, String requestFingerprint,
                           Map<String, Object> safeRequest, String decision, Instant expiresAt,
                           Instant createdAt, Instant resolvedAt, String resolutionCode) {}
    record ApprovalRequested(ApprovalSummary approval, long sequence, boolean replayed) {}
    record ApprovalResolution(ApprovalSummary approval, Long sequence, boolean replayed) {}
    record PolicyAuditDraft(String threadId, String turnId, String callId,
                            String capabilityClass, String subjectId,
                            String subjectFingerprint, String resourceScopeHash,
                            String decision, String errorCode, Instant createdAt) {}

    record FileRootDraft(String id, String workspaceId, String label, String canonicalPath,
                         String canonicalPathHash, String rootIdentity, String mode,
                         boolean enabled, boolean defaultRoot, Instant createdAt) {}
    record FileRootSummary(String id, String workspaceId, String label, String maskedPath,
                           String pathHash, String mode, boolean enabled, boolean defaultRoot,
                           long version, Instant createdAt, Instant updatedAt) {}
    record FileRootRecord(String id, String workspaceId, String label, String canonicalPath,
                          String pathHash, String rootIdentity, String mode, boolean enabled,
                          boolean defaultRoot, long version, Instant createdAt, Instant updatedAt) {}
}
