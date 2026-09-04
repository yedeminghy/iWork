package dev.agentdesktop.runtime.skill.importing;

import dev.agentdesktop.runtime.skill.SkillModels.SkillResourceDescriptor;

import java.time.Instant;
import java.util.List;

public final class SkillImportModels {
    private SkillImportModels() {}

    public enum ImportSourceType { LOCAL_DIRECTORY, GITHUB }
    public enum InstallAction { INSTALL, REPLACE, NO_OP }
    public enum InstallConflict { NONE, IDENTICAL, REPLACE_REQUIRED, WILL_BE_SHADOWED, CONFLICTED }
    public enum DependencyStatus { AVAILABLE, DISABLED, UNAVAILABLE, UNKNOWN }

    public record GitHubSkillSource(String repositoryUrl, String ref, String subdirectory) {}

    public record ImportSourceSummary(ImportSourceType type, String repositoryUrl, String requestedRef,
                                      String resolvedCommitSha, String subdirectory) {}

    public record SkillDependencyHint(String type, String value, DependencyStatus status) {}

    public record SkillImportManifest(String sessionId, ImportSourceSummary source, String name,
                                      String description, String license, String compatibility,
                                      String skillDocumentHash, String packageHash, long totalBytes,
                                      List<SkillResourceDescriptor> resources,
                                      List<SkillDependencyHint> dependencies, List<String> warningCodes,
                                      InstallConflict conflict, String expectedExistingContentHash,
                                      Instant expiresAt) {}

    public record SkillInstallResult(String skillId, String name, String operation, String packageHash,
                                     String contentHash, boolean enabled, boolean managed,
                                     boolean rollbackAvailable, Instant installedAt) {}

    public record ImportRecoveryResult(int recovered, int rolledBack, int quarantined) {}

    public record FetchedGitHubSkill(ImportSourceSummary source, String directoryName) {}
}
