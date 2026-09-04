package dev.agentdesktop.host;

import dev.agentdesktop.persistence.ProviderProfileRepository;
import dev.agentdesktop.runtime.provider.ProviderException;
import dev.agentdesktop.runtime.provider.ProviderModels.ModelProfile;
import dev.agentdesktop.runtime.provider.ProviderModels.ProviderCheckResult;
import dev.agentdesktop.runtime.provider.ProviderModels.ProviderStatus;
import dev.agentdesktop.runtime.provider.ProviderRegistry;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.GenerateOptions;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ProviderService {
    private final ProviderRegistry registry;
    private final ProviderSecretSessionStore secrets;
    private final ProviderProfileRepository states;

    public ProviderService(ProviderRegistry registry, ProviderSecretSessionStore secrets, ProviderProfileRepository states) {
        this.registry = registry;
        this.secrets = secrets;
        this.states = states;
        states.ensureProfiles(registry.profiles().stream()
                .map(profile -> new ProviderProfileRepository.ProfileSeed(profile.id(), profile.catalogVersion())).toList());
    }

    public List<ProviderProfileSummary> list() {
        Map<String, ProviderProfileRepository.ProfileState> current = states.list().stream()
                .collect(Collectors.toMap(ProviderProfileRepository.ProfileState::profileId, Function.identity()));
        return registry.profiles().stream().map(profile -> summary(profile, current.get(profile.id()))).toList();
    }

    public Mono<ProviderCheckResult> check(String profileId, String secretSessionId) {
        ModelProfile profile = registry.requireProfile(profileId);
        ProviderSecretSessionStore.ConsumedSecret secret = secrets.consume(secretSessionId, profile.providerId());
        Instant checkedAt = Instant.now();
        try {
            var model = registry.createModel(profileId, secret.value());
            Msg request = Msg.builder().name("provider-check").role(MsgRole.USER)
                    .textContent("Reply with OK.").build();
            return model.stream(List.of(request), List.of(), GenerateOptions.builder().stream(true).maxTokens(8).build())
                    .next().switchIfEmpty(Mono.error(new ProviderException("PROVIDER_PROTOCOL_ERROR")))
                    .timeout(Duration.ofSeconds(20))
                    .map(ignored -> success(profile, checkedAt))
                    .onErrorResume(error -> Mono.just(failure(profile, classify(error), checkedAt)))
                    .doFinally(ignored -> secret.close());
        } catch (RuntimeException exception) {
            secret.close();
            return Mono.just(failure(profile, classify(exception), checkedAt));
        }
    }

    private ProviderCheckResult success(ModelProfile profile, Instant checkedAt) {
        Set<String> values = profile.capabilities().stream().map(Enum::name).collect(Collectors.toSet());
        states.recordCheck(profile.id(), ProviderStatus.READY.name(), values, null, checkedAt, profile.catalogVersion());
        return new ProviderCheckResult(profile.id(), ProviderStatus.READY, profile.capabilities(), null, checkedAt);
    }

    private ProviderCheckResult failure(ModelProfile profile, String code, Instant checkedAt) {
        states.recordCheck(profile.id(), ProviderStatus.ERROR.name(), Set.of(), code, checkedAt, profile.catalogVersion());
        return new ProviderCheckResult(profile.id(), ProviderStatus.ERROR, Set.of(), code, checkedAt);
    }

    static String classify(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        if (current instanceof ProviderException provider) return provider.code();
        if (current instanceof TimeoutException || current instanceof SocketTimeoutException) return "PROVIDER_TIMEOUT";
        if (current instanceof ConnectException) return "PROVIDER_UNREACHABLE";
        String message = safeClassificationText(error).toLowerCase(Locale.ROOT);
        if (message.contains("401") || message.contains("403") || message.contains("unauthorized") || message.contains("forbidden")) return "PROVIDER_AUTH_FAILED";
        if (message.contains("404") || message.contains("model_not_found") || message.contains("model not found")) return "MODEL_NOT_AVAILABLE";
        if (message.contains("429") && (message.contains("quota") || message.contains("balance"))) return "PROVIDER_QUOTA_EXCEEDED";
        if (message.contains("429") || message.contains("rate limit")) return "PROVIDER_RATE_LIMITED";
        if (message.contains("timeout") || message.contains("timed out")) return "PROVIDER_TIMEOUT";
        if (message.contains("connect") || message.contains("unreachable") || message.contains("unknown host")) return "PROVIDER_UNREACHABLE";
        return "PROVIDER_PROTOCOL_ERROR";
    }

    private static String safeClassificationText(Throwable error) {
        StringBuilder value = new StringBuilder();
        for (Throwable current = error; current != null && value.length() < 2048; current = current.getCause()) {
            if (current.getMessage() != null) value.append(' ').append(current.getMessage(), 0, Math.min(current.getMessage().length(), 512));
        }
        return value.toString();
    }

    private static ProviderProfileSummary summary(ModelProfile profile, ProviderProfileRepository.ProfileState state) {
        return new ProviderProfileSummary(profile.id(), profile.providerId(), profile.displayName(), profile.modelId(),
                profile.adapter().name(), profile.region(), profile.capabilities().stream().map(Enum::name).collect(Collectors.toSet()),
                state == null ? Set.of() : state.observedCapabilities(), state == null ? "NOT_CONFIGURED" : state.status(),
                state == null ? null : state.lastErrorCode(), state == null ? null : state.lastCheckedAt(), profile.catalogVersion());
    }

    public record ProviderProfileSummary(String id, String providerId, String displayName, String modelId,
                                         String adapter, String region, Set<String> expectedCapabilities,
                                         Set<String> observedCapabilities, String status, String lastErrorCode,
                                         String lastCheckedAt, String catalogVersion) {}
}
