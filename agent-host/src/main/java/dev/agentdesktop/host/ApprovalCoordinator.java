package dev.agentdesktop.host;

import dev.agentdesktop.persistence.Ids;
import dev.agentdesktop.persistence.PermissionRepository;
import dev.agentdesktop.persistence.PermissionStore;
import dev.agentdesktop.runtime.permission.PermissionException;
import dev.agentdesktop.runtime.permission.PermissionModels.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bridges durable approval state to a non-blocking in-memory continuation. */
@Service
public final class ApprovalCoordinator {
    /** Must remain below AgentScope's five-minute tool execution timeout. */
    public static final Duration APPROVAL_TTL=Duration.ofMinutes(4);
    private final PermissionStore store;
    private final Clock clock;
    private final Map<String,Pending> pending=new ConcurrentHashMap<>();

    @Autowired public ApprovalCoordinator(PermissionRepository store){this(store,Clock.systemUTC());}
    ApprovalCoordinator(PermissionStore store,Clock clock){this.store=store;this.clock=clock;}

    public Awaiting request(PermissionRequest request){
        Instant now=Instant.now(clock);String approvalId=Ids.newId("apr");Instant expires=now.plus(APPROVAL_TTL);
        Map<String,Object> value=safeValue(approvalId,request,expires);
        PermissionStore.ApprovalDraft draft=new PermissionStore.ApprovalDraft(approvalId,request.threadId(),request.turnId(),
                request.callId(),request.subject().subjectId(),request.capability().name(),request.summary().risk(),
                request.invocationFingerprint(),request.subject().sourceFingerprint(),request.argumentsHash(),
                dev.agentdesktop.runtime.permission.PermissionFingerprints.sha256(request.resource().scopeClass()),value,expires,now);
        PermissionStore.ApprovalRequested persisted=store.requestApproval(draft);
        Pending handle=new Pending(request,Sinks.one());Pending existing=pending.putIfAbsent(persisted.approval().id(),handle);
        Pending effective=existing==null?handle:existing;
        Duration remaining=Duration.between(Instant.now(clock),persisted.approval().expiresAt());if(remaining.isNegative()||remaining.isZero())remaining=Duration.ofMillis(1);
        Mono<Resolution> resolution=effective.sink.asMono().timeout(remaining,
                Mono.defer(()->{
                    PermissionStore.ApprovalResolution expired=store.expireApproval(persisted.approval().id(),Instant.now(clock));
                    pending.remove(persisted.approval().id(),effective);
                    return Mono.just(restoredResolution(expired,effective.request));
                })).doFinally(ignored->pending.remove(persisted.approval().id(),effective));
        return new Awaiting(persisted.approval().id(),persisted.sequence(),value,resolution);
    }

    public PermissionStore.ApprovalResolution resolve(String approvalId,ApprovalDecision decision,String expectedFingerprint){
        PermissionStore.ApprovalResolution result=store.resolveApproval(approvalId,decision.name(),expectedFingerprint,Instant.now(clock));
        Pending handle=pending.get(approvalId);
        if(handle!=null&&!result.replayed())handle.sink.tryEmitValue(new Resolution(
                new ResolvedApproval(approvalId,decision,handle.request),result.sequence(),resolutionValue(result.approval()),null));
        return result;
    }

    public void cancelTurn(String turnId,String code){
        PermissionStore.ApprovalResolution result=store.cancelPendingForTurn(turnId,code,Instant.now(clock));
        if(result==null)return;Pending handle=pending.remove(result.approval().id());
        if(handle!=null)handle.sink.tryEmitError(new PermissionException(code));
    }

    public boolean isPending(String approvalId){return pending.containsKey(approvalId);}
    public int pendingCount(){return pending.size();}

    public void auditPolicyEvaluation(PermissionRequest request,String decision,String errorCode){
        store.recordPolicyEvaluation(new PermissionStore.PolicyAuditDraft(request.threadId(),request.turnId(),
                request.callId(),request.capability().name(),request.subject().subjectId(),
                request.subject().sourceFingerprint(),
                dev.agentdesktop.runtime.permission.PermissionFingerprints.sha256(request.resource().scopeClass()),
                decision,errorCode,Instant.now(clock)));
    }

    private static Map<String,Object> safeValue(String approvalId,PermissionRequest r,Instant expires){
        Map<String,Object> value=new LinkedHashMap<>();value.put("schemaVersion",1);value.put("approvalId",approvalId);
        value.put("toolCallId",r.callId());value.put("capability",r.capability().name());value.put("risk",r.summary().risk());
        value.put("title",r.summary().title());value.put("summary",r.summary().summary());value.put("source",r.summary().source());
        value.put("target",r.summary().target());value.put("choices",java.util.List.of("ALLOW_ONCE","ALLOW_THREAD","DENY"));
        value.put("requestFingerprint",r.invocationFingerprint());value.put("expiresAt",expires.toString());
        if(r.summary().warning()!=null&&!r.summary().warning().isBlank())value.put("warning",r.summary().warning());return Map.copyOf(value);
    }
    private static Map<String,Object> resolutionValue(PermissionStore.ApprovalSummary a){return Map.of("schemaVersion",1,"approvalId",a.id(),"toolCallId",a.callId(),"decision",a.decision(),"requestFingerprint",a.requestFingerprint());}
    private static Resolution restoredResolution(PermissionStore.ApprovalResolution result,PermissionRequest request){
        PermissionStore.ApprovalSummary approval=result.approval();
        if(java.util.List.of("ALLOW_ONCE","ALLOW_THREAD","DENY").contains(approval.decision())){
            return new Resolution(new ResolvedApproval(approval.id(),ApprovalDecision.valueOf(approval.decision()),request),
                    result.sequence(),resolutionValue(approval),null);
        }
        String code=approval.resolutionCode()==null||approval.resolutionCode().isBlank()?"TOOL_APPROVAL_CANCELLED":approval.resolutionCode();
        return new Resolution(null,result.sequence(),resolutionValue(approval),code);
    }

    public record Awaiting(String approvalId,long sequence,Map<String,Object> requiredValue,Mono<Resolution> resolution){}
    public record Resolution(ResolvedApproval approval,Long sequence,Map<String,Object> value,String errorCode){}
    private record Pending(PermissionRequest request,Sinks.One<Resolution> sink){}
}
