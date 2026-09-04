package dev.agentdesktop.runtime.skill;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.agentdesktop.runtime.HarnessToolBoundary;
import dev.agentdesktop.runtime.ProviderEvent;
import dev.agentdesktop.runtime.skill.SkillModels.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillHarnessBoundaryTest {
    @Test
    void routesActivationAndResourceReadsThroughApplicationBridge() {
        SkillMetadata metadata=new SkillMetadata("skl_indicator123","indicator-extraction","Extract approved indicators",SkillScope.USER);
        SkillResourceDescriptor defaults=new SkillResourceDescriptor("res_defaults123","references/default-information.md","references","text/markdown",22,"defaults-hash",false);
        FixtureBridge bridge=new FixtureBridge(metadata,defaults);
        SkillHarnessBoundary boundary=new SkillHarnessBoundary(new AgentScopeSkillAdapter(bridge),
                new SkillTurnContext("trn_indicator123","ws_default",1,List.of(metadata)));

        assertEquals(List.of(SkillHarnessBoundary.ACTIVATE_TOOL_ID,SkillHarnessBoundary.READ_RESOURCE_TOOL_ID),
                boundary.definitions().stream().map(HarnessToolBoundary.ToolDefinition::toolId).toList());
        assertTrue(boundary.definitions().stream().noneMatch(HarnessToolBoundary.ToolDefinition::userVisible));

        List<ProviderEvent> events=new ArrayList<>();
        var activateArgs=JsonNodeFactory.instance.objectNode().put("skillId",metadata.id());
        var activated=boundary.execute(new HarnessToolBoundary.ToolCall("call_activate","activate_local_skill",
                SkillHarnessBoundary.ACTIVATE_TOOL_ID,activateArgs),events::add).block(Duration.ofSeconds(2));
        assertNotNull(activated);
        assertEquals("SUCCEEDED",activated.status());
        assertEquals("Read references/default-information.md before execution",activated.modelResult().path("instructions").asText());
        assertEquals(defaults.id(),activated.modelResult().path("resources").get(0).path("resourceId").asText());
        assertInstanceOf(ProviderEvent.SkillActivated.class,events.getFirst());

        var readArgs=JsonNodeFactory.instance.objectNode().put("skillId",metadata.id()).put("resourceId",defaults.id());
        var loaded=boundary.execute(new HarnessToolBoundary.ToolCall("call_read","read_local_skill_resource",
                SkillHarnessBoundary.READ_RESOURCE_TOOL_ID,readArgs),events::add).block(Duration.ofSeconds(2));
        assertNotNull(loaded);
        assertEquals("calCode = NULL",loaded.modelResult().path("content").asText());
        assertInstanceOf(ProviderEvent.SkillResourceLoaded.class,events.getLast());
        assertEquals(1,bridge.activationCount);
        assertEquals(1,bridge.readCount);
    }

    private static final class FixtureBridge implements SkillRuntimeBridge {
        private final SkillMetadata metadata;
        private final SkillResourceDescriptor resource;
        private int activationCount;
        private int readCount;
        private FixtureBridge(SkillMetadata metadata,SkillResourceDescriptor resource){this.metadata=metadata;this.resource=resource;}
        @Override public SkillTurnContext prepare(String turnId,String workspaceId,List<String> explicitSkillIds){throw new UnsupportedOperationException();}
        @Override public ActivatedSkill activate(String turnId,String skillId,ActivationReason reason){
            activationCount++;
            assertEquals(metadata.id(),skillId);
            return new ActivatedSkill(metadata.id(),metadata.name(),metadata.scope(),"Read references/default-information.md before execution",
                    "skill-hash",List.of(resource),reason);
        }
        @Override public SkillResourceContent readResource(String turnId,String skillId,String resourceId){
            readCount++;
            assertEquals(metadata.id(),skillId);assertEquals(resource.id(),resourceId);
            return new SkillResourceContent(skillId,resourceId,resource.relativePath(),resource.kind(),resource.mediaType(),resource.byteSize(),resource.contentHash(),"calCode = NULL");
        }
        @Override public SkillScriptPlan resolveScript(String turnId,String skillId,String resourceId){throw new UnsupportedOperationException();}
        @Override public List<ActivatedSkill> activated(String turnId){return List.of();}
        @Override public void closeTurn(String turnId){}
    }
}
