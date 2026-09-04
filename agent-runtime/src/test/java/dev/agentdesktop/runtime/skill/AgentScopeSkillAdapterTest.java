package dev.agentdesktop.runtime.skill;

import dev.agentdesktop.runtime.skill.SkillModels.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentScopeSkillAdapterTest {
    @Test
    void bindsMetadataAndActivatedInstructionsWithoutEagerResourceReads() {
        SkillMetadata metadata=new SkillMetadata("skl_review123","review-skill","Review source code",SkillScope.WORKSPACE);
        SkillResourceDescriptor reference=new SkillResourceDescriptor("res_reference","references/format.md","references","text/markdown",14,"ref-hash",false);
        SkillResourceDescriptor script=new SkillResourceDescriptor("res_script","scripts/run.ps1","scripts","text/plain",12,"script-hash",true);
        ActivatedSkill activated=new ActivatedSkill(metadata.id(),metadata.name(),metadata.scope(),"PRIVATE INSTRUCTION BODY","hash",List.of(reference,script),ActivationReason.IMPLICIT);
        AgentScopeSkillAdapter adapter=new AgentScopeSkillAdapter(new NoopBridge());

        var metadataOnly=adapter.bind(new SkillTurnContext("trn_test123","ws_default",1,List.of(metadata)),List.of());
        assertNotNull(metadataOnly.builder());
        assertTrue(metadataOnly.contextPrompt().contains("activate_local_skill"));
        assertFalse(metadataOnly.contextPrompt().contains("PRIVATE"));

        var activeBinding=adapter.bind(new SkillTurnContext("trn_test123","ws_default",1,List.of(metadata)),List.of(activated));
        assertTrue(activeBinding.contextPrompt().contains("PRIVATE INSTRUCTION BODY"));
        assertTrue(activeBinding.contextPrompt().contains("references/format.md"));
        assertTrue(activeBinding.contextPrompt().contains("res_reference"));
        assertTrue(activeBinding.contextPrompt().contains("scripts/run.ps1"));
        assertFalse(activeBinding.contextPrompt().contains("REFERENCE BODY"));
    }

    private static final class NoopBridge implements SkillRuntimeBridge {
        @Override public SkillTurnContext prepare(String turnId,String workspaceId,List<String> explicitSkillIds){throw new UnsupportedOperationException();}
        @Override public ActivatedSkill activate(String turnId,String skillId,ActivationReason reason){throw new UnsupportedOperationException();}
        @Override public SkillResourceContent readResource(String turnId,String skillId,String resourceId){
            throw new AssertionError("Binding must not eagerly read Skill resources");
        }
        @Override public SkillScriptPlan resolveScript(String turnId,String skillId,String resourceId){throw new UnsupportedOperationException();}
        @Override public List<ActivatedSkill> activated(String turnId){return List.of();}
        @Override public void closeTurn(String turnId){}
    }
}
