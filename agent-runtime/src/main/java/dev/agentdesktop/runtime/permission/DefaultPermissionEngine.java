package dev.agentdesktop.runtime.permission;

import dev.agentdesktop.runtime.permission.PermissionModels.*;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Fail-closed policy implementation. Thread grants intentionally never leave memory. */
public final class DefaultPermissionEngine implements PermissionEngine {
    private final ConcurrentHashMap<String,Set<String>> threadGrants=new ConcurrentHashMap<>();
    private final Clock clock;
    private final boolean testFixtures;

    public DefaultPermissionEngine(Clock clock,boolean testFixtures){this.clock=clock;this.testFixtures=testFixtures;}
    public DefaultPermissionEngine(boolean testFixtures){this(Clock.systemUTC(),testFixtures);}

    @Override public PolicyEvaluation evaluate(PermissionRequest request){
        if(request==null||request.callId()==null||request.threadId()==null||request.turnId()==null||request.subject()==null)
            return new Denied("INVALID_PERMISSION_REQUEST");
        if(request.hardDenyCode()!=null)return new Denied(request.hardDenyCode());
        if(threadGrants.getOrDefault(request.threadId(),Set.of()).contains(request.threadGrantFingerprint()))
            return new Allowed(GrantSource.ALLOW_THREAD);
        if(testFixtures&&request.capability()==CapabilityClass.DEMO_SAFE)return new Allowed(GrantSource.TEST_FIXTURE);
        return new ApprovalRequired(request);
    }

    @Override public ExecutionPermit issueAllowed(PermissionRequest request,GrantSource source){
        return new ExecutionPermit(request.invocationFingerprint(),source,Instant.now(clock));
    }
    @Override public ExecutionPermit allowOnce(ResolvedApproval approval){
        require(approval,ApprovalDecision.ALLOW_ONCE);return issueAllowed(approval.request(),GrantSource.ALLOW_ONCE);
    }
    @Override public ExecutionPermit allowThread(ResolvedApproval approval){
        require(approval,ApprovalDecision.ALLOW_THREAD);
        threadGrants.computeIfAbsent(approval.request().threadId(),ignored->ConcurrentHashMap.newKeySet())
                .add(approval.request().threadGrantFingerprint());
        return issueAllowed(approval.request(),GrantSource.ALLOW_THREAD);
    }
    @Override public void clearThread(String threadId){if(threadId!=null)threadGrants.remove(threadId);}
    private static void require(ResolvedApproval approval,ApprovalDecision decision){
        if(approval==null||approval.decision()!=decision)throw new PermissionException("INVALID_APPROVAL_DECISION");
    }
}
