package dev.agentdesktop.host.api;

import dev.agentdesktop.persistence.ConversationRepository;
import dev.agentdesktop.persistence.ConversationStore;
import dev.agentdesktop.persistence.ThreadSnapshot;
import dev.agentdesktop.persistence.ThreadSummary;
import dev.agentdesktop.runtime.provider.ProviderRegistry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class ThreadController {
    private final ConversationStore store;
    private final ProviderRegistry providers;

    public ThreadController(ConversationRepository repository,ProviderRegistry providers) { this.store = repository;this.providers=providers; }

    @GetMapping("/api/v1/threads")
    public ConversationStore.ThreadPage list(@RequestParam(defaultValue = "ACTIVE") String status,
                                             @RequestParam(defaultValue = "50") int limit,
                                             @RequestParam(required = false) String cursor) {
        return store.listThreads(new ConversationStore.ThreadQuery(status, limit, cursor));
    }

    @GetMapping("/api/v1/threads/{threadId}/snapshot")
    public ThreadSnapshot snapshot(@PathVariable String threadId) { return store.loadThreadSnapshot(threadId); }

    @PatchMapping("/api/v1/threads/{threadId}")
    public ThreadSummary rename(@PathVariable String threadId, @RequestBody RenameRequest request) {
        return store.renameThread(threadId, request.title());
    }

    @DeleteMapping("/api/v1/threads/{threadId}")
    public void archive(@PathVariable String threadId) { store.setThreadArchived(threadId, true); }

    @PostMapping("/api/v1/threads/{threadId}:restore")
    public ThreadSummary restore(@PathVariable String threadId) { return store.setThreadArchived(threadId, false); }

    @PatchMapping("/api/v1/threads/{threadId}/profile")
    public ThreadSummary selectProfile(@PathVariable String threadId,@RequestBody SelectProfileRequest request){
        providers.requireProfile(request.profileId());
        return store.selectThreadProfile(threadId,request.profileId());
    }

    public record RenameRequest(String title) {}
    public record SelectProfileRequest(String profileId) {}
}
