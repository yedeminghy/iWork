package dev.agentdesktop.host.api;

import dev.agentdesktop.runtime.skill.SkillModels.*;
import dev.agentdesktop.runtime.skill.SkillRegistry;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public final class SkillController {
    private final SkillRegistry registry;
    public SkillController(SkillRegistry registry){this.registry=registry;}
    @GetMapping("/skills") public Mono<SkillPage> list(@RequestParam(required=false)SkillScope scope,@RequestParam(required=false)SkillStatus status){return blocking(()->registry.list(new SkillQuery(scope,status)));}
    @GetMapping("/skills/{id}") public Mono<SkillDetail> get(@PathVariable String id){return blocking(()->registry.get(id,true));}
    @GetMapping("/skills/{id}/resources") public Mono<List<SkillResourceDescriptor>> resources(@PathVariable String id){return blocking(()->registry.listResources(id));}
    @GetMapping("/skills/{id}/resources/{resourceId}") public Mono<SkillResourceView> resource(@PathVariable String id,@PathVariable String resourceId){return blocking(()->registry.readResourceForView(id,resourceId));}
    @PostMapping("/skills:refresh") public Mono<SkillRefreshResult> refresh(@RequestBody(required=false)RefreshRequest request){SkillScope scope=request==null||request.scope()==null?SkillScope.WORKSPACE:request.scope();return blocking(()->registry.refresh(scope,"ws_default"));}
    @PatchMapping("/skills/{id}") public Mono<SkillSummary> enabled(@PathVariable String id,@RequestBody UpdateRequest request){return blocking(()->registry.setEnabled(id,request.enabled()));}
    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> work){return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());}
    public record RefreshRequest(SkillScope scope){}
    public record UpdateRequest(boolean enabled){}
}
