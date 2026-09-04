package dev.agentdesktop.runtime.provider;

import java.time.Instant;
import java.util.Set;

public final class ProviderModels {
    private ProviderModels() {}

    public enum ProviderAdapter { OPENAI_COMPATIBLE, DASHSCOPE }
    public enum ProviderCapability { STREAM_TEXT, STRUCTURED_TOOL_CALL, CANCEL }
    public enum ProviderStatus { NOT_CONFIGURED, READY, ERROR }

    public record ModelProfile(String id, String providerId, String displayName, String modelId,
                               ProviderAdapter adapter, String region,
                               Set<ProviderCapability> capabilities, String catalogVersion) {
        public ModelProfile {
            capabilities = Set.copyOf(capabilities);
        }
    }

    public record ProviderCheckResult(String profileId, ProviderStatus status,
                                      Set<ProviderCapability> observedCapabilities,
                                      String errorCode, Instant checkedAt) {
        public ProviderCheckResult {
            observedCapabilities = Set.copyOf(observedCapabilities);
        }
    }
}
