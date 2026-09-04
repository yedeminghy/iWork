package dev.agentdesktop.runtime.skill.importing;

import java.nio.file.Path;

import static dev.agentdesktop.runtime.skill.importing.SkillImportModels.*;

public interface GitHubSkillFetcher {
    FetchedGitHubSkill fetch(GitHubSkillSource source, Path emptyStagingDirectory);
}
