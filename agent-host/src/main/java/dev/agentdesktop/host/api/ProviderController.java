package dev.agentdesktop.host.api;

import dev.agentdesktop.host.ProviderSecretSessionStore;
import dev.agentdesktop.host.ProviderService;
import dev.agentdesktop.runtime.provider.ProviderModels.ProviderCheckResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
public final class ProviderController {
    private final ProviderSecretSessionStore secrets;
    private final ProviderService providers;

    public ProviderController(ProviderSecretSessionStore secrets, ProviderService providers) {
        this.secrets = secrets; this.providers = providers;
    }

    @GetMapping("/api/v1/providers")
    public List<ProviderService.ProviderProfileSummary> list() { return providers.list(); }

    @PostMapping("/api/v1/provider-secret-sessions")
    public ProviderSecretSessionStore.SecretSession createSecret(@Valid @RequestBody CreateSecretRequest request) {
        return secrets.create(request.providerId(), request.apiKey());
    }

    @PostMapping("/api/v1/providers/{profileId}:check")
    public Mono<ProviderCheckResult> check(@PathVariable String profileId, @Valid @RequestBody CheckRequest request) {
        return providers.check(profileId, request.secretSessionId());
    }

    public record CreateSecretRequest(@Pattern(regexp = "^(deepseek|qwen)$") String providerId,
                                      @NotBlank @Size(max = 16384) String apiKey) {}
    public record CheckRequest(@Pattern(regexp = "^pss_[A-Za-z0-9_-]{32}$") String secretSessionId) {}
}
