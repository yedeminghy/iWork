package dev.agentdesktop.runtime.skill.importing;

import java.nio.file.Path;

import static dev.agentdesktop.runtime.skill.importing.SkillImportModels.*;

public interface SkillImportService {
    SkillImportManifest inspectLocal(Path selectedByMain);
    SkillImportManifest inspectGitHub(GitHubSkillSource source);
    SkillInstallResult commit(String sessionId, InstallAction action, String expectedExistingContentHash);
    SkillInstallResult remove(String skillId);
    SkillInstallResult rollback(String skillId);
    void cancel(String sessionId);
    ImportRecoveryResult recover();
}
