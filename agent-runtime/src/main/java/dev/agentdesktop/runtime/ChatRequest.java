package dev.agentdesktop.runtime;

import dev.agentdesktop.runtime.skill.SkillModels.ActivatedSkill;
import dev.agentdesktop.runtime.skill.SkillModels.SkillMetadata;
import dev.agentdesktop.runtime.skill.SkillModels.SkillResourceContent;
import java.util.List;
import dev.agentdesktop.runtime.skill.AgentScopeSkillAdapter.HarnessTurnBinding;

public record ChatRequest(String threadId, String turnId, String message,
                          List<SkillMetadata> skillCatalog,
                          List<ActivatedSkill> activatedSkills,
                          List<SkillResourceContent> loadedResources,
                          HarnessTurnBinding harnessBinding) {
    public ChatRequest { skillCatalog=List.copyOf(skillCatalog);activatedSkills=List.copyOf(activatedSkills);loadedResources=List.copyOf(loadedResources); }
    public ChatRequest(String threadId,String turnId,String message,List<SkillMetadata> skillCatalog,List<ActivatedSkill> activatedSkills,List<SkillResourceContent> loadedResources){this(threadId,turnId,message,skillCatalog,activatedSkills,loadedResources,null);}
    public ChatRequest(String threadId,String turnId,String message){this(threadId,turnId,message,List.of(),List.of(),List.of(),null);}
}
