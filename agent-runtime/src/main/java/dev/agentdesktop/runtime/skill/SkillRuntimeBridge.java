package dev.agentdesktop.runtime.skill;

import dev.agentdesktop.runtime.skill.SkillModels.*;
import java.util.List;

public interface SkillRuntimeBridge {
    SkillTurnContext prepare(String turnId, String workspaceId, List<String> explicitSkillIds);
    ActivatedSkill activate(String turnId, String skillId, ActivationReason reason);
    SkillResourceContent readResource(String turnId, String skillId, String resourceId);
    SkillScriptPlan resolveScript(String turnId, String skillId, String resourceId);
    List<ActivatedSkill> activated(String turnId);
    void closeTurn(String turnId);
}
