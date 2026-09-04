package dev.agentdesktop.host.api;

import dev.agentdesktop.host.ApprovalCoordinator;
import dev.agentdesktop.persistence.PermissionStore;
import dev.agentdesktop.runtime.permission.PermissionModels.ApprovalDecision;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public final class ApprovalController {
    private final ApprovalCoordinator coordinator;
    public ApprovalController(ApprovalCoordinator coordinator){this.coordinator=coordinator;}

    @PostMapping("/api/v1/approvals/{approvalId}:resolve")
    public Map<String,Object> resolve(@PathVariable String approvalId,@RequestBody ResolveRequest request){
        if(!approvalId.matches("apr_[A-Za-z0-9_-]{8,80}")||request==null||request.decision()==null||
                request.expectedRequestFingerprint()==null||!request.expectedRequestFingerprint().matches("sha256:[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid approval request");
        ApprovalDecision decision;try{decision=ApprovalDecision.valueOf(request.decision());}catch(Exception e){throw new IllegalArgumentException("Invalid approval decision");}
        PermissionStore.ApprovalResolution result=coordinator.resolve(approvalId,decision,request.expectedRequestFingerprint());
        return Map.of("approvalId",result.approval().id(),"decision",result.approval().decision(),
                "requestFingerprint",result.approval().requestFingerprint(),"replayed",result.replayed());
    }
    public record ResolveRequest(String decision,String expectedRequestFingerprint){}
}
