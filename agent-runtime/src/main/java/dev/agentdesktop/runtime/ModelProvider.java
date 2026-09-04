package dev.agentdesktop.runtime;

import reactor.core.publisher.Flux;

public interface ModelProvider {
    String id();
    Flux<ProviderEvent> stream(ChatRequest request);
}

