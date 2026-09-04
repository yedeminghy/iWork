package dev.agentdesktop.host.api;

import dev.agentdesktop.host.FileRootService;
import dev.agentdesktop.persistence.PermissionStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public final class FileRootController {
    private final FileRootService service;
    public FileRootController(FileRootService service){this.service=service;}
    @GetMapping("/api/v1/workspaces/{workspaceId}/file-roots") public List<PermissionStore.FileRootSummary> list(@PathVariable String workspaceId){return service.list(workspaceId);}
    @PostMapping("/api/v1/workspaces/{workspaceId}/file-roots") public PermissionStore.FileRootSummary add(@PathVariable String workspaceId,@RequestBody AddRequest request){if(request==null)throw new IllegalArgumentException();return service.add(workspaceId,request.selectedPath(),request.mode());}
    @PatchMapping("/api/v1/file-roots/{rootId}") public PermissionStore.FileRootSummary update(@PathVariable String rootId,@RequestBody UpdateRequest request){if(request==null)throw new IllegalArgumentException();return service.update(rootId,request.mode(),request.enabled(),request.expectedVersion());}
    @DeleteMapping("/api/v1/file-roots/{rootId}") public void remove(@PathVariable String rootId,@RequestParam long expectedVersion){service.remove(rootId,expectedVersion);}
    public record AddRequest(String selectedPath,String mode){}
    public record UpdateRequest(String mode,boolean enabled,long expectedVersion){}
}
