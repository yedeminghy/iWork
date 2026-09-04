package dev.agentdesktop.runtime.skill;

import dev.agentdesktop.runtime.skill.SkillModels.*;
import java.util.List;

public interface SkillRegistry {
    SkillPage list(SkillQuery query);
    SkillDetail get(String skillId, boolean includeInstructions);
    List<SkillResourceDescriptor> listResources(String skillId);
    SkillResourceView readResourceForView(String skillId, String resourceId);
    SkillRefreshResult refresh(SkillScope scope, String workspaceId);
    SkillSummary setEnabled(String skillId, boolean enabled);
    SkillCatalogSnapshot snapshot(String workspaceId);
}
