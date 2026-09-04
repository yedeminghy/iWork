package dev.agentdesktop.host.api;

import dev.agentdesktop.runtime.skill.importing.SkillImportModels.GitHubSkillSource;
import dev.agentdesktop.runtime.skill.importing.SkillImportModels.InstallAction;
import dev.agentdesktop.runtime.skill.importing.SkillImportModels.SkillImportManifest;
import dev.agentdesktop.runtime.skill.importing.SkillImportModels.SkillInstallResult;
import dev.agentdesktop.runtime.skill.importing.SkillImportService;
import dev.agentdesktop.runtime.skill.SkillRegistry;
import dev.agentdesktop.persistence.SkillInstallRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.Objects;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
public final class SkillImportController {
    private final SkillImportService service;
    private final SkillRegistry registry;
    private final SkillInstallRepository repository;

    public SkillImportController(SkillImportService service,SkillRegistry registry,SkillInstallRepository repository) {
        this.service = Objects.requireNonNull(service);this.registry=Objects.requireNonNull(registry);this.repository=Objects.requireNonNull(repository);
    }

    @PostMapping("/skill-imports:inspect-local")
    public Mono<SkillImportManifest> inspectLocal(@RequestBody InspectLocalRequest request) {
        if (request == null || request.selectedPath() == null || request.selectedPath().isBlank()
                || request.selectedPath().length() > 32767) throw new IllegalArgumentException("Invalid local source");
        Path selected = Path.of(request.selectedPath());
        if (!selected.isAbsolute()) throw new IllegalArgumentException("Invalid local source");
        return blocking(() -> service.inspectLocal(selected));
    }

    @PostMapping("/skill-imports:inspect-github")
    public Mono<SkillImportManifest> inspectGitHub(@RequestBody InspectGitHubRequest request) {
        if (request == null || request.repositoryUrl() == null || request.repositoryUrl().length() > 512
                || length(request.ref()) > 256 || length(request.subdirectory()) > 512) {
            throw new IllegalArgumentException("Invalid GitHub source");
        }
        return blocking(() -> service.inspectGitHub(new GitHubSkillSource(request.repositoryUrl(), request.ref(), request.subdirectory())));
    }

    @PostMapping("/skill-imports/{sessionId}:commit")
    public Mono<SkillInstallResult> commit(@PathVariable String sessionId, @RequestBody CommitRequest request) {
        if (request == null || request.action() == null || length(request.expectedExistingContentHash()) > 64) {
            throw new IllegalArgumentException("Invalid commit request");
        }
        return blocking(() -> service.commit(sessionId, request.action(), request.expectedExistingContentHash()));
    }

    @DeleteMapping("/skill-imports/{sessionId}")
    public Mono<Void> cancel(@PathVariable String sessionId) {
        return blocking(() -> { service.cancel(sessionId); return null; });
    }

    @DeleteMapping("/skills/{skillId}/installation")
    public Mono<SkillInstallResult> remove(@PathVariable String skillId) {
        return blocking(() -> service.remove(skillId));
    }

    @PostMapping("/skills/{skillId}/installation:rollback")
    public Mono<SkillInstallResult> rollback(@PathVariable String skillId) {
        return blocking(() -> service.rollback(skillId));
    }

    @GetMapping("/skills/{skillId}/installation")
    public Mono<InstallationInfo> installation(@PathVariable String skillId){
        return blocking(()->{
            var skill=registry.get(skillId,false);
            var install=repository.findByName(skill.name()).filter(SkillInstallRepository.ManagedInstall::managedByApp).orElse(null);
            if(install==null||install.currentRevisionId()==null)return new InstallationInfo(false,null,null,null,null,false);
            var revision=repository.findRevision(install.currentRevisionId()).orElse(null);
            if(revision==null)return new InstallationInfo(false,null,null,null,null,false);
            return new InstallationInfo(true,revision.sourceType(),revision.repositoryUrl(),revision.resolvedCommitSha(),revision.installedAt(),install.backupRevisionId()!=null);
        });
    }

    private static int length(String value) { return value == null ? 0 : value.length(); }
    private static <T> Mono<T> blocking(java.util.concurrent.Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(Schedulers.boundedElastic());
    }

    public record InspectLocalRequest(String selectedPath) {}
    public record InspectGitHubRequest(String repositoryUrl, String ref, String subdirectory) {}
    public record CommitRequest(InstallAction action, String expectedExistingContentHash) {}
    public record InstallationInfo(boolean managed,String sourceType,String repositoryUrl,String resolvedCommitSha,Instant installedAt,boolean rollbackAvailable){}
}
