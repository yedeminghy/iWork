package dev.agentdesktop.runtime.skill;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;

public final class SkillModels {
    private SkillModels() {}

    public enum SkillScope { BUILTIN, USER, WORKSPACE }
    public enum SkillStatus { VALID, INVALID, SHADOWED, MISSING }
    public enum ActivationReason { EXPLICIT, IMPLICIT }

    public record SkillQuery(SkillScope scope, SkillStatus status) {}
    public record SkillPage(List<SkillSummary> items, long generation) {}
    public record SkillSummary(String id, String name, String description, SkillScope scope,
                               SkillStatus status, boolean enabled, String compatibility,
                               List<String> warningCodes, String lastErrorCode,
                               String shadowedById, long generation) {}
    public record SkillDetail(String id, String name, String description, SkillScope scope,
                              SkillStatus status, boolean enabled, String license,
                              String compatibility, Map<String, String> metadata,
                              String allowedTools, Map<String, Object> extensions,
                              List<String> warningCodes, String lastErrorCode,
                              String shadowedById, String contentHash, String instructions,
                              List<SkillResourceDescriptor> resources) {}
    public record SkillResourceDescriptor(String id, String relativePath, String kind,
                                          String mediaType, long byteSize, String contentHash,
                                          boolean executableCandidate) {}
    public record SkillResourceView(String skillId, String resourceId, String relativePath,
                                    String kind, String mediaType, long byteSize,
                                    String contentHash, boolean binary, String content) {}
    public record SkillRefreshResult(SkillScope scope, long generation, int valid,
                                     int invalid, int shadowed, int missing,
                                     String status, String errorCode) {}
    public record SkillMetadata(String id, String name, String description, SkillScope scope) {}
    public record SkillCatalogSnapshot(String workspaceId, long generation,
                                       List<SkillMetadata> skills) {}
    public record SkillTurnContext(String turnId, String workspaceId, long generation,
                                   List<SkillMetadata> catalog) {}
    public record ActivatedSkill(String id, String name, SkillScope scope, String instructions,
                                 String contentHash, List<SkillResourceDescriptor> resources,
                                 ActivationReason reason) {}
    public record SkillResourceContent(String skillId, String resourceId, String relativePath,
                                       String kind, String mediaType, long byteSize,
                                       String contentHash, String content) {}
    public record SkillScriptPlan(String skillId,String resourceId,String skillName,Path scriptPath,
                                  String contentHash,String interpreterProfile) {}
}
