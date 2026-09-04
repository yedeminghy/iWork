package dev.agentdesktop.runtime.permission;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PermissionModels {
    private PermissionModels() {}

    public enum CapabilityClass { READ, WRITE, EXECUTE, NETWORK, UNKNOWN, DEMO_SAFE }
    public enum ApprovalDecision { ALLOW_ONCE, ALLOW_THREAD, DENY }
    public enum GrantSource { POLICY, TEST_FIXTURE, ALLOW_ONCE, ALLOW_THREAD }

    public record PermissionSubject(String kind,String subjectId,String displayName,String sourceId,
                                    String sourceFingerprint) {}
    public record ResourceScope(String rootId,String relativeTarget,String endpointOrigin,String skillId,
                                String resourceId,String contentHash,String scopeClass) {}
    public record SafeInvocationSummary(String risk,String title,String summary,Map<String,Object> source,
                                        Map<String,Object> target,String warning) {}
    public record PermissionRequest(String callId,String threadId,String turnId,CapabilityClass capability,
                                    PermissionSubject subject,ResourceScope resource,String argumentsHash,
                                    String invocationFingerprint,String threadGrantFingerprint,
                                    SafeInvocationSummary summary,String hardDenyCode,Instant requestedAt) {}

    public sealed interface PolicyEvaluation permits Allowed,ApprovalRequired,Denied {}
    public record Allowed(GrantSource source) implements PolicyEvaluation {}
    public record ApprovalRequired(PermissionRequest request) implements PolicyEvaluation {}
    public record Denied(String errorCode) implements PolicyEvaluation {}
    public record ResolvedApproval(String approvalId,ApprovalDecision decision,PermissionRequest request) {}

    /** Unforgeable runtime object. Adapters must consume it exactly once immediately before side effects. */
    public static final class ExecutionPermit {
        private final String invocationFingerprint;
        private final GrantSource source;
        private final Instant issuedAt;
        private final AtomicBoolean consumed=new AtomicBoolean();
        ExecutionPermit(String invocationFingerprint,GrantSource source,Instant issuedAt){
            this.invocationFingerprint=invocationFingerprint;this.source=source;this.issuedAt=issuedAt;
        }
        public String invocationFingerprint(){return invocationFingerprint;}
        public GrantSource source(){return source;}
        public Instant issuedAt(){return issuedAt;}
        public void consume(String expectedFingerprint){
            if(!invocationFingerprint.equals(expectedFingerprint))throw new PermissionException("APPROVAL_STALE");
            if(!consumed.compareAndSet(false,true))throw new PermissionException("PERMISSION_PERMIT_CONSUMED");
        }
    }
}
