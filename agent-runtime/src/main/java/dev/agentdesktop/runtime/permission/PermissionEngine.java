package dev.agentdesktop.runtime.permission;

import dev.agentdesktop.runtime.permission.PermissionModels.*;

public interface PermissionEngine {
    PolicyEvaluation evaluate(PermissionRequest request);
    ExecutionPermit issueAllowed(PermissionRequest request, GrantSource source);
    ExecutionPermit allowOnce(ResolvedApproval approval);
    ExecutionPermit allowThread(ResolvedApproval approval);
    void clearThread(String threadId);
}
