package dev.agentdesktop.runtime.provider;

import dev.agentdesktop.runtime.provider.ProviderModels.ModelProfile;
import io.agentscope.core.formatter.openai.DeepSeekFormatter;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.OpenAIChatModel;

import java.util.List;

/** Resolves only profiles present in the signed application catalog. */
public final class ProviderRegistry {
    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String DASHSCOPE_BASE_URL = "https://dashscope.aliyuncs.com/api/v1";
    private final ProviderCatalog catalog;

    public ProviderRegistry(ProviderCatalog catalog) { this.catalog = catalog; }

    public List<ModelProfile> profiles() { return catalog.profiles(); }
    public ModelProfile requireProfile(String profileId) { return catalog.require(profileId); }

    public Model createModel(String profileId, String apiKey) {
        ModelProfile profile = requireProfile(profileId);
        String secret = requireSecret(apiKey);
        return switch (profile.adapter()) {
            case OPENAI_COMPATIBLE -> OpenAIChatModel.builder()
                    .apiKey(secret).modelName(profile.modelId()).baseUrl(DEEPSEEK_BASE_URL)
                    .formatter(new DeepSeekFormatter()).stream(true).build();
            case DASHSCOPE -> DashScopeChatModel.builder()
                    .apiKey(secret).modelName(profile.modelId()).baseUrl(DASHSCOPE_BASE_URL)
                    .enableThinking(false).enableSearch(false).stream(true).build();
        };
    }

    private static String requireSecret(String value) {
        if (value == null || value.isBlank() || value.length() > 16_384 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new ProviderException("PROVIDER_KEY_MISSING");
        }
        return value;
    }
}
