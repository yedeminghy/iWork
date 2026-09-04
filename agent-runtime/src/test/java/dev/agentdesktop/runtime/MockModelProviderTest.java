package dev.agentdesktop.runtime;

import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import dev.agentdesktop.runtime.skill.SkillModels.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MockModelProviderTest {
    @Test
    void streamsTextAndCompletion() {
        StepVerifier.create(new MockModelProvider(Duration.ZERO)
                        .stream(new ChatRequest("thread", "turn", "hi")))
                .expectNextCount("Mock response: hi".length())
                .expectNext(new ProviderEvent.Completed())
                .verifyComplete();
    }

    @Test
    void selectsImplicitSkillFromDescriptionWithoutReceivingBodyOrSkillIdInMessage() {
        SkillMetadata review=new SkillMetadata("skl_review123","implicit-code-review","Review source code when a code review is requested",SkillScope.WORKSPACE);
        ChatRequest request=new ChatRequest("thread","turn","Please review this source code",List.of(review),List.of(),List.of());
        StepVerifier.create(new MockModelProvider(Duration.ZERO).stream(request))
                .assertNext(event->assertEquals(new ProviderEvent.StructuredSkillActivate(review.id()),event))
                .verifyComplete();
    }

    @Test
    void selectsOnlyMatchingResourceAfterActivation() {
        SkillResourceDescriptor needed=new SkillResourceDescriptor("res_needed123","references/needed.md","references","text/markdown",10,"hash",false);
        SkillResourceDescriptor unused=new SkillResourceDescriptor("res_unused123","references/unused.md","references","text/markdown",10,"hash2",false);
        ActivatedSkill active=new ActivatedSkill("skl_resource123","resource-on-demand",SkillScope.WORKSPACE,"BODY","content",List.of(needed,unused),ActivationReason.IMPLICIT);
        ChatRequest request=new ChatRequest("thread","turn","Load the needed reference",List.of(),List.of(active),List.of());
        StepVerifier.create(new MockModelProvider(Duration.ZERO).stream(request))
                .assertNext(event->assertEquals(new ProviderEvent.StructuredSkillReadResource(active.id(),needed.id()),event))
                .verifyComplete();
    }

    @Test
    void injectsStableStreamFailureOnlyInFixtureMode() {
        StepVerifier.create(new MockModelProvider(Duration.ZERO,true)
                        .stream(new ChatRequest("thread","turn","__week9_provider_stream_broken__")))
                .expectNext(new ProviderEvent.TextDelta("partial"))
                .expectErrorSatisfies(error->assertEquals("PROVIDER_STREAM_BROKEN",error.getMessage()))
                .verify();
    }
}
