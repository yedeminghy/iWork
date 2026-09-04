package dev.agentdesktop.runtime.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Validates one isolated Agent Skill package without changing the catalog or database. */
public interface SkillPackageValidator {
    ValidatedSkillPackage validate(Path isolatedSkillRoot);

    record ValidatedSkillPackage(
            String name,
            String description,
            String license,
            String compatibility,
            Map<String, String> metadata,
            String allowedTools,
            Map<String, Object> extensions,
            String skillDocumentHash,
            String packageHash,
            long totalBytes,
            List<String> warningCodes,
            List<ValidatedSkillResource> resources) {}

    record ValidatedSkillResource(
            String relativePath,
            String kind,
            String mediaType,
            long byteSize,
            String contentHash,
            boolean executableCandidate) {}
}
