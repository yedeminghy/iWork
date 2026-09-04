package dev.agentdesktop.host.api;

import dev.agentdesktop.host.TurnCoordinator;
import dev.agentdesktop.host.bootstrap.BootstrapConfig;
import dev.agentdesktop.persistence.ConversationRepository;
import dev.agentdesktop.persistence.ConversationStore;
import jakarta.validation.Valid;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;

@RestController
public final class HostController {
    private final TurnCoordinator coordinator;
    private final ConversationRepository repository;
    private final ConfigurableApplicationContext applicationContext;

    public HostController(TurnCoordinator coordinator, ConversationRepository repository,
                          ConfigurableApplicationContext applicationContext) {
        this.coordinator = coordinator;
        this.repository = repository;
        this.applicationContext = applicationContext;
    }

    @GetMapping("/internal/health")
    public HealthResponse health() {
        return new HealthResponse("READY", BootstrapConfig.SUPPORTED_PROTOCOL);
    }

    @PostMapping(value = "/agui/run", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<AgUiEvent>> run(@Valid @RequestBody RunAgentInput input) {
        return coordinator.run(input).map(event -> ServerSentEvent.builder(event)
                .id(Long.toString(event.sequence())).event(event.type()).build());
    }

    @PostMapping("/api/v1/turns/{turnId}:cancel")
    public TurnOperationResult cancel(@PathVariable String turnId) {
        var result = coordinator.cancel(turnId);
        return new TurnOperationResult(result.turnId(), result.status());
    }

    @PostMapping("/api/v1/turns/{turnId}:steer")
    public ConversationStore.AppendResult steer(@PathVariable String turnId,@Valid @RequestBody SteerRequest request){return coordinator.steer(turnId,request.message());}

    @PostMapping("/api/v1/turns/{turnId}:retry")
    public ConversationStore.RetryPreparation retry(@PathVariable String turnId){return repository.prepareRetry(turnId);}

    @GetMapping("/api/v1/threads/{threadId}/items")
    public List<ConversationRepository.ItemRecord> items(@PathVariable String threadId) {
        return repository.listVisibleItems(threadId);
    }

    @PostMapping("/internal/shutdown")
    public Mono<Void> shutdown() {
        Mono.delay(Duration.ofMillis(100), Schedulers.boundedElastic())
                .doOnNext(ignored -> applicationContext.close()).subscribe();
        return Mono.empty();
    }

    public record HealthResponse(String status, int protocolVersion) {}
    public record TurnOperationResult(String turnId, String status) {}
    public record SteerRequest(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max=4096) String message) {}
}
