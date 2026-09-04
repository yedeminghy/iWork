package dev.agentdesktop.host.api;

import dev.agentdesktop.mcp.McpClientManager;
import dev.agentdesktop.mcp.McpModels.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController @RequestMapping("/api/v1/mcp")
public final class McpController {
    private final McpClientManager manager;
    public McpController(McpClientManager manager){this.manager=manager;}
    @GetMapping("/servers") public Mono<McpServerPage> list(@RequestParam(defaultValue="50")int limit,@RequestParam(required=false)String cursor){return blocking(()->manager.list(limit,cursor));}
    @PostMapping("/servers") public Mono<McpServerSummary> save(@RequestBody ServerDraftRequest request){return blocking(()->manager.save(request.toDraft()));}
    @PatchMapping("/servers/{id}") public Mono<McpServerSummary> update(@PathVariable String id,@RequestBody ServerDraftRequest request){return blocking(()->manager.save(request.withId(id).toDraft()));}
    @PostMapping("/servers/{id}:connect") public Mono<McpServerSummary> connect(@PathVariable String id,@RequestBody(required=false)ConnectRequest request){return blocking(()->manager.connect(id,request==null?EphemeralCredential.empty():new EphemeralCredential(request.bearerToken(),request.environment())));}
    @PostMapping("/servers/{id}:disconnect") public Mono<Void> disconnect(@PathVariable String id){return blocking(()->{manager.disconnect(id);return true;}).then();}
    @DeleteMapping("/servers/{id}") public Mono<Void> delete(@PathVariable String id){return blocking(()->{manager.delete(id);return true;}).then();}
    @GetMapping("/servers/{id}/tools") public Mono<List<ToolDescriptor>> tools(@PathVariable String id){return blocking(()->manager.listTools(id));}
    @GetMapping("/servers/{id}/diagnostic") public Mono<McpDiagnostic> diagnostic(@PathVariable String id){return blocking(()->manager.recentDiagnostic(id));}
    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> work){return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());}
    public record ConnectRequest(String bearerToken,Map<String,String> environment){public ConnectRequest{environment=environment==null?Map.of():Map.copyOf(environment);}}
    public record ServerDraftRequest(String id,String name,String transport,Boolean enabled,String endpoint,String executable,List<String> arguments,String workingDirectory,List<String> allowedEnvironmentNames,String credentialRef,Integer maxAttempts,Long initialBackoffMs,Long maxBackoffMs){
        ServerDraftRequest withId(String v){return new ServerDraftRequest(v,name,transport,enabled,endpoint,executable,arguments,workingDirectory,allowedEnvironmentNames,credentialRef,maxAttempts,initialBackoffMs,maxBackoffMs);}
        McpServerDraft toDraft(){var policy=new ReconnectPolicy(maxAttempts==null?3:maxAttempts,Duration.ofMillis(initialBackoffMs==null?250:initialBackoffMs),Duration.ofMillis(maxBackoffMs==null?2000:maxBackoffMs));boolean on=enabled==null||enabled;
            if("STDIO".equals(transport))return new StdioServerDraft(id,name,executable,arguments==null?List.of():List.copyOf(arguments),workingDirectory==null?null:Path.of(workingDirectory),allowedEnvironmentNames==null?List.of():List.copyOf(allowedEnvironmentNames),on,policy);
            if("STREAMABLE_HTTP".equals(transport))return new StreamableHttpServerDraft(id,name,URI.create(endpoint),credentialRef,on,policy);
            throw new IllegalArgumentException("Unsupported MCP transport");}
    }
}
