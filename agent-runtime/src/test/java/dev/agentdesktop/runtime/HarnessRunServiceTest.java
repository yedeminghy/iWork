package dev.agentdesktop.runtime;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HarnessRunServiceTest {
    @Test
    void boundsEachProviderStreamAndMapsIdleTimeout() {
        Model never = new Model() {
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.never();
            }

            @Override public String getModelName() { return "fixture"; }
        };

        StepVerifier.withVirtualTime(() -> HarnessRunService.boundedModel(never, Duration.ofSeconds(2))
                        .stream(List.of(), List.of(), null))
                .expectSubscription()
                .thenAwait(Duration.ofSeconds(2))
                .expectErrorMatches(error -> error instanceof dev.agentdesktop.runtime.provider.ProviderException provider
                        && "PROVIDER_TIMEOUT".equals(provider.code()))
                .verify();
    }

    @Test
    void mapsWrappedProviderIoFailureToUnreachable() {
        Model broken = new Model() {
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.error(new RuntimeException("provider wrapper",new IOException("connection reset")));
            }

            @Override public String getModelName() { return "fixture"; }
        };

        StepVerifier.create(HarnessRunService.boundedModel(broken,Duration.ofSeconds(2)).stream(List.of(),List.of(),null))
                .expectErrorMatches(error -> error instanceof dev.agentdesktop.runtime.provider.ProviderException provider
                        && "PROVIDER_UNREACHABLE".equals(provider.code()))
                .verify();
    }

    @Test
    void disablesAutomaticProviderRetries() {
        var config = HarnessRunService.modelExecutionConfig(Duration.ofSeconds(45));
        assertEquals(Duration.ofSeconds(45), config.getTimeout());
        assertEquals(1, config.getMaxAttempts());
    }

    @Test
    void sendsBoundedConversationHistoryBeforeCurrentUserMessage() {
        List<Msg> messages=HarnessRunService.modelMessages(List.of(
                new HarnessRunService.ConversationMessage("user","查询夏普比率算法说明"),
                new HarnessRunService.ConversationMessage("assistant","夏普比率等于超额收益除以收益波动率")
        ),"上面算法说明，生成Word报告");

        assertEquals(List.of(MsgRole.USER,MsgRole.ASSISTANT,MsgRole.USER),messages.stream().map(Msg::getRole).toList());
        assertEquals(List.of("查询夏普比率算法说明","夏普比率等于超额收益除以收益波动率","上面算法说明，生成Word报告"),
                messages.stream().map(message->message.getContentBlocks(TextBlock.class).stream().map(TextBlock::getText).reduce("",String::concat)).toList());
    }

    @Test
    void appendsApplicationOwnedSkillContextToSystemPrompt() {
        var binding=new dev.agentdesktop.runtime.skill.AgentScopeSkillAdapter.HarnessTurnBinding(
                io.agentscope.harness.agent.HarnessAgent.builder(),"Available local Skills:\n- indicator-extraction");
        String prompt=HarnessRunService.systemPrompt(binding);
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("Available local Skills"));
        org.junit.jupiter.api.Assertions.assertTrue(prompt.contains("indicator-extraction"));
    }
}
