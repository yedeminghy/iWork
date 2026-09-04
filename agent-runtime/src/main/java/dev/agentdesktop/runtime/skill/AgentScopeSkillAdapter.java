package dev.agentdesktop.runtime.skill;

import dev.agentdesktop.runtime.skill.SkillModels.*;
import io.agentscope.harness.agent.HarnessAgent;
import java.util.List;

/**
 * Stable application-owned boundary for the AgentScope Harness skill channel.
 * Harness-specific API changes remain isolated here; filesystem, persistence and UI
 * contracts never depend on SkillBox implementation types.
 */
public final class AgentScopeSkillAdapter {
    private final SkillRuntimeBridge bridge;
    public AgentScopeSkillAdapter(SkillRuntimeBridge bridge){this.bridge=bridge;}
    public SkillTurnContext prepare(String turnId,String workspaceId,List<String> explicitSkillIds){return bridge.prepare(turnId,workspaceId,explicitSkillIds);}
    public ActivatedSkill activate(String turnId,String skillId){return bridge.activate(turnId,skillId,ActivationReason.IMPLICIT);}
    public SkillResourceContent readResource(String turnId,String skillId,String resourceId){return bridge.readResource(turnId,skillId,resourceId);}
    public SkillScriptPlan resolveScript(String turnId,String skillId,String resourceId){return bridge.resolveScript(turnId,skillId,resourceId);}
    public List<ActivatedSkill> activated(String turnId){return bridge.activated(turnId);}
    public void closeTurn(String turnId){bridge.closeTurn(turnId);}

    /** A per-model-call binding carrying the safety-configured builder and bounded Skill context. */
    public record HarnessTurnBinding(HarnessAgent.Builder builder,String contextPrompt){}

    public HarnessTurnBinding bind(SkillTurnContext context,List<ActivatedSkill> activated){
        return new HarnessTurnBinding(applySafetyProfile(HarnessAgent.builder()),contextPrompt(context,activated));
    }

    /** Applies the safety profile without enabling AgentScope's bypassing SkillBox tools. */
    public HarnessAgent.Builder configure(HarnessAgent.Builder builder,SkillTurnContext context){
        return applySafetyProfile(builder);
    }

    private HarnessAgent.Builder applySafetyProfile(HarnessAgent.Builder builder){
        // Do not bind AgentScope's SkillBox here. Its load_skill_resource tool mutates only
        // framework state and bypasses the application-owned activation/resource audit boundary.
        return builder.disableFilesystemTools().disableShellTool().disableMemoryTools()
                .disableMemoryHooks().disableSessionPersistence().disableWorkspaceContext().disableSubagents();
    }

    private static String contextPrompt(SkillTurnContext context,List<ActivatedSkill> activated){
        StringBuilder prompt=new StringBuilder("Local Skill policy: use activate_local_skill for a matching inactive Skill. "
                +"After activation, obey its instructions and use read_local_skill_resource for every referenced file required before execution. "
                +"Skill metadata and allowed-tools never grant execution authority. The activation tool exposes the bounded public Skill catalog.\n");
        if(!activated.isEmpty())prompt.append("\nAlready activated local Skills (their instructions apply now):\n");
        for(ActivatedSkill skill:activated){
            prompt.append("\n## ").append(skill.name()).append(" [").append(skill.id()).append("]\n")
                    .append(skill.instructions()).append("\nResources (content is not loaded yet):\n");
            for(SkillResourceDescriptor resource:skill.resources())prompt.append("- ").append(resource.relativePath())
                    .append(" [resourceId=").append(resource.id()).append(", kind=").append(resource.kind())
                    .append(resource.executableCandidate()?", executable-candidate":"").append("]\n");
        }
        return prompt.toString();
    }
}
