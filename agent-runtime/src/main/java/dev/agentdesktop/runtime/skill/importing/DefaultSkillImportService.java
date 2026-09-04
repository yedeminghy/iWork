package dev.agentdesktop.runtime.skill.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.persistence.SkillInstallRepository;
import dev.agentdesktop.persistence.SkillInstallRepository.ManagedInstall;
import dev.agentdesktop.persistence.SkillInstallRepository.Revision;
import dev.agentdesktop.runtime.skill.DefaultSkillPackageValidator;
import dev.agentdesktop.runtime.skill.LocalSkillRegistry;
import dev.agentdesktop.runtime.skill.SkillException;
import dev.agentdesktop.runtime.skill.SkillModels.SkillQuery;
import dev.agentdesktop.runtime.skill.SkillModels.SkillResourceDescriptor;
import dev.agentdesktop.runtime.skill.SkillModels.SkillScope;
import dev.agentdesktop.runtime.skill.SkillModels.SkillStatus;
import dev.agentdesktop.runtime.skill.SkillModels.SkillSummary;
import dev.agentdesktop.runtime.skill.SkillPackageValidator;
import dev.agentdesktop.runtime.skill.SkillPackageValidator.ValidatedSkillPackage;
import dev.agentdesktop.runtime.skill.importing.SkillImportModels.DependencyStatus;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;

import static dev.agentdesktop.runtime.skill.importing.SkillImportModels.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

public final class DefaultSkillImportService implements SkillImportService {
    static final int MAX_ACTIVE_SESSIONS = 3;
    static final long MAX_STAGING_BYTES = 100L * 1024 * 1024;
    static final Duration SESSION_TTL = Duration.ofMinutes(15);
    static final Duration ORPHAN_TTL = Duration.ofHours(24);

    private final Path appHome;
    private final Path userRoot;
    private final Path stagingRoot;
    private final Path workRoot;
    private final Path backupRoot;
    private final Path quarantineRoot;
    private final SkillPackageValidator validator;
    private final LocalSkillRegistry registry;
    private final SkillInstallRepository repository;
    private final GitHubSkillFetcher gitHubFetcher;
    private final Function<String, DependencyStatus> dependencyResolver;
    private final Clock clock;
    private final Map<String, ImportSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> nameLocks = new ConcurrentHashMap<>();
    private final Semaphore sessionPermits = new Semaphore(MAX_ACTIVE_SESSIONS);

    public DefaultSkillImportService(Path appHome, ObjectMapper mapper, LocalSkillRegistry registry,
                                     SkillInstallRepository repository, GitHubSkillFetcher gitHubFetcher,
                                     Function<String, DependencyStatus> dependencyResolver) {
        this(appHome, new DefaultSkillPackageValidator(mapper), registry, repository, gitHubFetcher,
                dependencyResolver, Clock.systemUTC());
    }

    DefaultSkillImportService(Path appHome, SkillPackageValidator validator, LocalSkillRegistry registry,
                              SkillInstallRepository repository, GitHubSkillFetcher gitHubFetcher,
                              Function<String, DependencyStatus> dependencyResolver, Clock clock) {
        this.appHome = normalize(appHome);
        this.userRoot = this.appHome.resolve("skills");
        this.stagingRoot = this.appHome.resolve("import-staging");
        this.workRoot = this.appHome.resolve("skill-install-work");
        this.backupRoot = this.appHome.resolve("skill-backups");
        this.quarantineRoot = this.appHome.resolve("skill-quarantine");
        this.validator = Objects.requireNonNull(validator);
        this.registry = Objects.requireNonNull(registry);
        this.repository = Objects.requireNonNull(repository);
        this.gitHubFetcher = Objects.requireNonNull(gitHubFetcher);
        this.dependencyResolver = dependencyResolver == null ? ignored -> DependencyStatus.UNKNOWN : dependencyResolver;
        this.clock = Objects.requireNonNull(clock);
        createManagedDirectories();
    }

    @Override
    public SkillImportManifest inspectLocal(Path selectedByMain) {
        Objects.requireNonNull(selectedByMain);
        expireSessions();
        reserveSessionSlot();
        String sessionId = randomId("imp");
        Path sessionRoot = stagingRoot.resolve(sessionId);
        try {
            Path source = normalize(selectedByMain);
            String directoryName = source.getFileName() == null ? "" : source.getFileName().toString();
            if (!DefaultSkillPackageValidator.validSkillName(directoryName)) {
                throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID", "SKILL_NAME_INVALID");
            }
            Path stagedSkill = sessionRoot.resolve(directoryName);
            Files.createDirectories(stagedSkill);
            copyLocalSource(source, stagedSkill);
            ImportSourceSummary summary = new ImportSourceSummary(ImportSourceType.LOCAL_DIRECTORY,
                    null, null, null, null);
            return completeInspection(sessionId, stagedSkill, summary);
        } catch (SkillImportException exception) {
            discardFailedInspection(sessionId, sessionRoot);
            throw exception;
        } catch (SkillException exception) {
            discardFailedInspection(sessionId, sessionRoot);
            throw invalidPackage(exception);
        } catch (Exception exception) {
            discardFailedInspection(sessionId, sessionRoot);
            throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID", exception);
        }
    }

    @Override
    public SkillImportManifest inspectGitHub(GitHubSkillSource source) {
        Objects.requireNonNull(source);
        expireSessions();
        reserveSessionSlot();
        String sessionId = randomId("imp");
        Path sessionRoot = stagingRoot.resolve(sessionId);
        try {
            Files.createDirectories(sessionRoot);
            FetchedGitHubSkill fetched = gitHubFetcher.fetch(source, sessionRoot);
            if (!DefaultSkillPackageValidator.validSkillName(fetched.directoryName())) {
                throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID", "SKILL_NAME_INVALID");
            }
            Path stagedSkill = sessionRoot.resolve(fetched.directoryName()).normalize();
            if (!stagedSkill.getParent().equals(sessionRoot) || !Files.isDirectory(stagedSkill, NOFOLLOW_LINKS)) {
                throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID");
            }
            return completeInspection(sessionId, stagedSkill, fetched.source());
        } catch (SkillImportException exception) {
            discardFailedInspection(sessionId, sessionRoot);
            throw exception;
        } catch (SkillException exception) {
            discardFailedInspection(sessionId, sessionRoot);
            throw invalidPackage(exception);
        } catch (Exception exception) {
            discardFailedInspection(sessionId, sessionRoot);
            throw new SkillImportException("SKILL_IMPORT_NETWORK_FAILED", exception);
        }
    }

    @Override
    public SkillInstallResult commit(String sessionId, InstallAction action, String expectedExistingContentHash) {
        if (action == null) throw new SkillImportException("SKILL_INSTALL_CONFLICT");
        ImportSession session = requireSession(sessionId);
        if (clock.instant().isAfter(session.expiresAt())) {
            cancel(sessionId);
            throw new SkillImportException("SKILL_IMPORT_EXPIRED");
        }
        ReentrantLock lock = nameLocks.computeIfAbsent(session.validated().name(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            ValidatedSkillPackage currentStaging = validator.validate(session.stagedSkillRoot());
            if (!constantTimeEquals(currentStaging.packageHash(), session.validated().packageHash())) {
                throw new SkillImportException("SKILL_IMPORT_STALE");
            }
            ConflictState conflict = conflict(currentStaging.name(), currentStaging.packageHash());
            if (action == InstallAction.NO_OP) {
                if (conflict.conflict() != InstallConflict.IDENTICAL) throw new SkillImportException("SKILL_INSTALL_CONFLICT");
                sessions.remove(sessionId);
                sessionPermits.release();
                safeDelete(session.sessionRoot(), stagingRoot);
                SkillSummary summary = findUserSkill(currentStaging.name());
                return result(summary, "NO_OP", currentStaging, repository.findByName(currentStaging.name()).isPresent(), clock.instant());
            }
            if (action == InstallAction.INSTALL && conflict.conflict() != InstallConflict.NONE
                    && conflict.conflict() != InstallConflict.WILL_BE_SHADOWED) {
                throw new SkillImportException("SKILL_INSTALL_CONFLICT");
            }
            if (action == InstallAction.REPLACE) {
                if (conflict.conflict() != InstallConflict.REPLACE_REQUIRED) throw new SkillImportException("SKILL_INSTALL_CONFLICT");
                if (!constantTimeEquals(nullToEmpty(expectedExistingContentHash), nullToEmpty(conflict.existingContentHash()))) {
                    throw new SkillImportException("SKILL_INSTALL_CONFLICT");
                }
            }
            return install(session, currentStaging, action, conflict);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public SkillInstallResult remove(String skillId) {
        var detail = registry.get(skillId, false);
        SkillSummary summary = findUserSkill(detail.name());
        if (!summary.id().equals(skillId)) throw new SkillImportException("SKILL_INSTALL_NOT_MANAGED");
        if (summary.scope() != SkillScope.USER) throw new SkillImportException("SKILL_INSTALL_NOT_MANAGED");
        ManagedInstall install = repository.findByName(summary.name())
                .filter(ManagedInstall::managedByApp).orElseThrow(() -> new SkillImportException("SKILL_INSTALL_NOT_MANAGED"));
        Revision active = repository.findRevision(install.currentRevisionId())
                .orElseThrow(() -> new SkillImportException("SKILL_INSTALL_NOT_MANAGED"));
        ReentrantLock lock = nameLocks.computeIfAbsent(summary.name(), ignored -> new ReentrantLock());
        lock.lock();
        String transactionId = randomId("sit");
        Path target = userRoot.resolve(summary.name());
        Path trash = workRoot.resolve(transactionId).resolve("trash").resolve(summary.name());
        try {
            repository.beginTransaction(transactionId, summary.name(), "REMOVE", active.packageHash(), active.packageHash(), clock.instant());
            Files.createDirectories(trash.getParent());
            atomicMove(target, trash);
            repository.updateTransaction(transactionId, "MOVED", null, clock.instant());
            registry.refresh(SkillScope.USER, "ws_default");
            repository.markRemoved(install.id(), active.id(), clock.instant());
            repository.updateTransaction(transactionId, "COMMITTED", null, clock.instant());
            safeDelete(workRoot.resolve(transactionId), workRoot);
            return new SkillInstallResult(skillId, summary.name(), "REMOVE", active.packageHash(),
                    active.skillContentHash(), false, true, install.backupRevisionId() != null, clock.instant());
        } catch (Exception exception) {
            try {
                if (!Files.exists(target, NOFOLLOW_LINKS) && Files.exists(trash, NOFOLLOW_LINKS)) atomicMove(trash, target);
                registry.refresh(SkillScope.USER, "ws_default");
            } catch (Exception ignored) { }
            repository.updateTransaction(transactionId, "FAILED", errorCode(exception), clock.instant());
            throw translateInstall(exception);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public SkillInstallResult rollback(String skillId) {
        var detail = registry.get(skillId, false);
        SkillSummary summary = findUserSkill(detail.name());
        if (!summary.id().equals(skillId)) throw new SkillImportException("SKILL_INSTALL_NOT_MANAGED");
        if (summary.scope() != SkillScope.USER) throw new SkillImportException("SKILL_INSTALL_NOT_MANAGED");
        ManagedInstall install = repository.findByName(summary.name())
                .filter(ManagedInstall::managedByApp).orElseThrow(() -> new SkillImportException("SKILL_INSTALL_NOT_MANAGED"));
        Revision current = repository.findRevision(install.currentRevisionId())
                .orElseThrow(() -> new SkillImportException("SKILL_INSTALL_BACKUP_NOT_FOUND"));
        Revision backup = repository.findRevision(install.backupRevisionId())
                .filter(item -> item.backupKey() != null).orElseThrow(() -> new SkillImportException("SKILL_INSTALL_BACKUP_NOT_FOUND"));
        Path storedBackup = backupRoot.resolve(summary.name()).resolve(backup.backupKey());
        if (!Files.isDirectory(storedBackup, NOFOLLOW_LINKS)) throw new SkillImportException("SKILL_INSTALL_BACKUP_NOT_FOUND");
        ReentrantLock lock = nameLocks.computeIfAbsent(summary.name(), ignored -> new ReentrantLock());
        lock.lock();
        String transactionId = randomId("sit");
        Path target = userRoot.resolve(summary.name());
        Path newBackup = backupRoot.resolve(summary.name()).resolve(transactionId);
        try {
            repository.beginTransaction(transactionId, summary.name(), "ROLLBACK", backup.packageHash(), current.packageHash(), clock.instant());
            Files.createDirectories(newBackup.getParent());
            atomicMove(target, newBackup);
            repository.updateTransaction(transactionId, "BACKED_UP", null, clock.instant());
            atomicMove(storedBackup, target);
            repository.updateTransaction(transactionId, "MOVED", null, clock.instant());
            ValidatedSkillPackage validated = validator.validate(target);
            if (!constantTimeEquals(validated.packageHash(), backup.packageHash())) throw new SkillImportException("SKILL_IMPORT_STALE");
            registry.refresh(SkillScope.USER, "ws_default");
            SkillSummary rolledBack = findUserSkill(summary.name());
            registry.setEnabled(rolledBack.id(), false);
            repository.commitRollback(install.id(), backup.id(), current.id(), transactionId, clock.instant());
            repository.updateTransaction(transactionId, "COMMITTED", null, clock.instant());
            return result(findUserSkill(rolledBack.name()), "ROLLBACK", validated, true, clock.instant());
        } catch (Exception exception) {
            try {
                if (Files.exists(target, NOFOLLOW_LINKS)) {
                    Path quarantine = quarantineRoot.resolve(transactionId).resolve(summary.name());
                    Files.createDirectories(quarantine.getParent());
                    atomicMove(target, quarantine);
                }
                if (Files.exists(newBackup, NOFOLLOW_LINKS)) atomicMove(newBackup, target);
                registry.refresh(SkillScope.USER, "ws_default");
            } catch (Exception ignored) { }
            repository.updateTransaction(transactionId, "FAILED", errorCode(exception), clock.instant());
            throw translateInstall(exception);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void cancel(String sessionId) {
        ImportSession removed = sessions.remove(sessionId);
        if (removed != null) { sessionPermits.release(); safeDelete(removed.sessionRoot(), stagingRoot); }
    }

    @Override
    public ImportRecoveryResult recover() {
        int rolledBack = 0;
        int quarantined = 0;
        for (var transaction : repository.unfinishedTransactions()) {
            Path target = userRoot.resolve(transaction.skillName());
            Path incoming = workRoot.resolve(transaction.id()).resolve(transaction.skillName());
            Path backup = backupRoot.resolve(transaction.skillName()).resolve(transaction.id());
            try {
                if (!Files.exists(target, NOFOLLOW_LINKS) && Files.exists(backup, NOFOLLOW_LINKS)) {
                    Files.createDirectories(target.getParent());
                    atomicMove(backup, target);
                    rolledBack++;
                } else if (Files.exists(target, NOFOLLOW_LINKS) && Files.exists(incoming, NOFOLLOW_LINKS)) {
                    Path quarantine = quarantineRoot.resolve(transaction.id()).resolve(transaction.skillName());
                    Files.createDirectories(quarantine.getParent());
                    atomicMove(incoming, quarantine);
                    quarantined++;
                }
                safeDelete(workRoot.resolve(transaction.id()), workRoot);
                repository.updateTransaction(transaction.id(), "FAILED", "SKILL_INSTALL_RECOVERED", clock.instant());
            } catch (Exception exception) {
                repository.updateTransaction(transaction.id(), "FAILED", "SKILL_INSTALL_ROLLBACK_FAILED", clock.instant());
                quarantined++;
            }
        }
        cleanupOrphanStaging();
        registry.refresh(SkillScope.USER, "ws_default");
        return new ImportRecoveryResult(rolledBack + quarantined, rolledBack, quarantined);
    }

    private SkillImportManifest completeInspection(String sessionId, Path stagedSkill, ImportSourceSummary source) {
        ValidatedSkillPackage validated = validator.validate(stagedSkill);
        enforceImportLimits(validated);
        ConflictState conflict = conflict(validated.name(), validated.packageHash());
        Instant expiresAt = clock.instant().plus(SESSION_TTL);
        List<SkillResourceDescriptor> resources = descriptors(sessionId, validated);
        List<String> warnings = new ArrayList<>(validated.warningCodes());
        warnings.addAll(conflict.warningCodes());
        List<SkillDependencyHint> dependencies = dependencyHints(stagedSkill);
        ImportSession session = new ImportSession(sessionId, stagedSkill.getParent(), stagedSkill, source, validated, expiresAt);
        sessions.put(sessionId, session);
        enforceStagingBudget(session);
        return new SkillImportManifest(sessionId, source, validated.name(), validated.description(), validated.license(),
                validated.compatibility(), validated.skillDocumentHash(), validated.packageHash(), validated.totalBytes(),
                resources, dependencies, List.copyOf(warnings), conflict.conflict(), conflict.existingContentHash(), expiresAt);
    }

    private SkillInstallResult install(ImportSession session, ValidatedSkillPackage validated,
                                       InstallAction action, ConflictState conflict) {
        String transactionId = randomId("sit");
        String operation = action == InstallAction.REPLACE ? "REPLACE" : "INSTALL";
        Path target = userRoot.resolve(validated.name());
        Path incoming = workRoot.resolve(transactionId).resolve(validated.name());
        Path backup = backupRoot.resolve(validated.name()).resolve(transactionId);
        String previousPackageHash = conflict.existingPackageHash();
        repository.beginTransaction(transactionId, validated.name(), operation, validated.packageHash(), previousPackageHash, clock.instant());
        try {
            Files.createDirectories(incoming.getParent());
            copyManagedTree(session.stagedSkillRoot(), incoming);
            ValidatedSkillPackage copied = validator.validate(incoming);
            if (!constantTimeEquals(copied.packageHash(), validated.packageHash())) throw new SkillImportException("SKILL_IMPORT_STALE");
            Revision backupRevision = null;
            ManagedInstall existingInstall = repository.findByName(validated.name()).orElse(null);
            if (Files.exists(target, NOFOLLOW_LINKS)) {
                Files.createDirectories(backup.getParent());
                atomicMove(target, backup);
                repository.updateTransaction(transactionId, "BACKED_UP", null, clock.instant());
                Revision existingRevision = existingInstall == null ? null : repository.findRevision(existingInstall.currentRevisionId()).orElse(null);
                backupRevision = revisionFromExisting(existingRevision, existingInstall, transactionId, validated.name(),
                        previousPackageHash, conflict.existingContentHash());
            }
            atomicMove(incoming, target);
            repository.updateTransaction(transactionId, "MOVED", null, clock.instant());
            ValidatedSkillPackage installed = validator.validate(target);
            if (!constantTimeEquals(installed.packageHash(), validated.packageHash())) throw new SkillImportException("SKILL_IMPORT_STALE");
            registry.refresh(SkillScope.USER, "ws_default");
            SkillSummary summary = findUserSkill(validated.name());
            if (summary.status() != SkillStatus.VALID && summary.status() != SkillStatus.SHADOWED) {
                throw new SkillImportException("SKILL_IMPORT_INVALID_SKILL", summary.lastErrorCode());
            }
            summary = registry.setEnabled(summary.id(), false);
            String installationId = existingInstall == null ? randomId("ins") : existingInstall.id();
            Revision active = new Revision(randomId("rev"), installationId, "ACTIVE", session.source().type().name(),
                    session.source().repositoryUrl(), session.source().requestedRef(), session.source().resolvedCommitSha(),
                    session.source().subdirectory(), installed.packageHash(), installed.skillDocumentHash(), null, clock.instant());
            if (backupRevision != null && !backupRevision.installationId().equals(installationId)) {
                backupRevision = new Revision(backupRevision.id(), installationId, backupRevision.state(), backupRevision.sourceType(),
                        backupRevision.repositoryUrl(), backupRevision.requestedRef(), backupRevision.resolvedCommitSha(),
                        backupRevision.sourceSubdirectory(), backupRevision.packageHash(), backupRevision.skillContentHash(),
                        backupRevision.backupKey(), backupRevision.installedAt());
            }
            repository.commitInstall(installationId, validated.name(), active, backupRevision, clock.instant());
            repository.updateTransaction(transactionId, "COMMITTED", null, clock.instant());
            sessions.remove(session.id());
            sessionPermits.release();
            safeDelete(session.sessionRoot(), stagingRoot);
            safeDelete(workRoot.resolve(transactionId), workRoot);
            return result(summary, operation, installed, true, clock.instant());
        } catch (Exception exception) {
            try {
                if (Files.exists(target, NOFOLLOW_LINKS)) {
                    Path quarantine = quarantineRoot.resolve(transactionId).resolve(validated.name());
                    Files.createDirectories(quarantine.getParent());
                    atomicMove(target, quarantine);
                }
                if (Files.exists(backup, NOFOLLOW_LINKS)) atomicMove(backup, target);
                registry.refresh(SkillScope.USER, "ws_default");
            } catch (Exception ignored) { }
            repository.updateTransaction(transactionId, "FAILED", errorCode(exception), clock.instant());
            throw translateInstall(exception);
        }
    }

    private Revision revisionFromExisting(Revision existing, ManagedInstall install, String backupKey,
                                          String skillName, String packageHash, String contentHash) {
        String installationId = install == null ? randomId("ins") : install.id();
        if (existing != null) {
            return new Revision(existing.id(), installationId, "BACKUP", existing.sourceType(), existing.repositoryUrl(),
                    existing.requestedRef(), existing.resolvedCommitSha(), existing.sourceSubdirectory(),
                    existing.packageHash(), existing.skillContentHash(), backupKey, existing.installedAt());
        }
        return new Revision(randomId("rev"), installationId, "BACKUP", ImportSourceType.LOCAL_DIRECTORY.name(),
                null, null, null, null, packageHash == null ? hashText(skillName) : packageHash,
                contentHash == null ? hashText(skillName + "|content") : contentHash, backupKey, clock.instant());
    }

    private ConflictState conflict(String name, String packageHash) {
        Path target = userRoot.resolve(name);
        List<String> warnings = new ArrayList<>();
        boolean workspace = registry.list(new SkillQuery(SkillScope.WORKSPACE, null)).items().stream().anyMatch(item -> item.name().equals(name));
        boolean builtin = registry.list(new SkillQuery(SkillScope.BUILTIN, null)).items().stream().anyMatch(item -> item.name().equals(name));
        if (workspace) warnings.add("SKILL_IMPORT_WILL_BE_SHADOWED");
        if (builtin) warnings.add("SKILL_IMPORT_OVERRIDES_BUILTIN");
        if (!Files.exists(target, NOFOLLOW_LINKS)) {
            return new ConflictState(workspace ? InstallConflict.WILL_BE_SHADOWED : InstallConflict.NONE,
                    null, null, List.copyOf(warnings));
        }
        try {
            ValidatedSkillPackage existing = validator.validate(target);
            InstallConflict value = constantTimeEquals(existing.packageHash(), packageHash)
                    ? InstallConflict.IDENTICAL : InstallConflict.REPLACE_REQUIRED;
            return new ConflictState(value, existing.packageHash(), existing.skillDocumentHash(), List.copyOf(warnings));
        } catch (SkillException exception) {
            warnings.add(exception.code());
            return new ConflictState(InstallConflict.CONFLICTED, null, null, List.copyOf(warnings));
        }
    }

    private void copyLocalSource(Path sourceRoot, Path targetRoot) throws IOException {
        if (isLinkLike(sourceRoot) || !Files.isDirectory(sourceRoot, NOFOLLOW_LINKS)) {
            throw new SkillImportException("SKILL_IMPORT_SOURCE_INVALID");
        }
        Path realRoot = sourceRoot.toRealPath();
        Set<String> caseFolded = new HashSet<>();
        int files = 0;
        long total = 0;
        try (Stream<Path> stream = Files.walk(sourceRoot, DefaultSkillPackageValidator.MAX_DEPTH + 2)) {
            for (Path source : stream.sorted().toList()) {
                if (source.equals(sourceRoot)) continue;
                Path relative = sourceRoot.relativize(source);
                if (relative.getNameCount() > DefaultSkillPackageValidator.MAX_DEPTH) {
                    throw new SkillImportException("SKILL_IMPORT_PACKAGE_TOO_LARGE");
                }
                String portable = relative.toString().replace('\\', '/');
                if (!caseFolded.add(portable.toLowerCase(Locale.ROOT))) throw new SkillImportException("SKILL_IMPORT_CASE_COLLISION");
                if (isLinkLike(source)) throw new SkillImportException("SKILL_IMPORT_UNSUPPORTED_ENTRY");
                BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class, NOFOLLOW_LINKS);
                Path real = source.toRealPath();
                if (!real.startsWith(realRoot)) throw new SkillImportException("SKILL_IMPORT_UNSUPPORTED_ENTRY");
                Path target = targetRoot.resolve(relative).normalize();
                if (!target.startsWith(targetRoot)) throw new SkillImportException("SKILL_IMPORT_UNSUPPORTED_ENTRY");
                if (before.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                if (!before.isRegularFile()) throw new SkillImportException("SKILL_IMPORT_UNSUPPORTED_ENTRY");
                files++;
                total += before.size();
                if (files > DefaultSkillPackageValidator.MAX_RESOURCES + 1 || total > DefaultSkillPackageValidator.MAX_PACKAGE_BYTES) {
                    throw new SkillImportException("SKILL_IMPORT_PACKAGE_TOO_LARGE");
                }
                int max = portable.equals("SKILL.md") ? DefaultSkillPackageValidator.MAX_SKILL_BYTES : DefaultSkillPackageValidator.MAX_RESOURCE_BYTES;
                if (before.size() > max) throw new SkillImportException("SKILL_IMPORT_PACKAGE_TOO_LARGE");
                Files.createDirectories(target.getParent());
                String sourceHashBefore = copyAndHash(real, target);
                BasicFileAttributes after = Files.readAttributes(source, BasicFileAttributes.class, NOFOLLOW_LINKS);
                String sourceHashAfter = hashFile(real);
                String targetHash = hashFile(target);
                if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                        || !constantTimeEquals(sourceHashBefore, sourceHashAfter) || !constantTimeEquals(sourceHashBefore, targetHash)) {
                    throw new SkillImportException("SKILL_IMPORT_SOURCE_CHANGED");
                }
            }
        }
    }

    private void copyManagedTree(Path sourceRoot, Path targetRoot) throws IOException {
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            for (Path source : stream.sorted().toList()) {
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative).normalize();
                if (!target.startsWith(targetRoot) || Files.isSymbolicLink(source)) {
                    throw new SkillImportException("SKILL_IMPORT_UNSUPPORTED_ENTRY");
                }
                BasicFileAttributes attributes = Files.readAttributes(source, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (attributes.isDirectory()) Files.createDirectories(target);
                else if (attributes.isRegularFile()) {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                } else throw new SkillImportException("SKILL_IMPORT_UNSUPPORTED_ENTRY");
            }
        }
    }

    private List<SkillDependencyHint> dependencyHints(Path skillRoot) {
        Path file = skillRoot.resolve("agents").resolve("openai.yaml");
        if (!Files.isRegularFile(file, NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) return List.of();
        try {
            byte[] bytes = DefaultSkillPackageValidator.boundedRead(file, 64 * 1024, "SKILL_RESOURCE_TOO_LARGE");
            String text = DefaultSkillPackageValidator.decodeUtf8(bytes, "SKILL_INVALID_FRONTMATTER");
            if (DefaultSkillPackageValidator.containsSecret(text)) throw new SkillException("SKILL_SECRET_DETECTED");
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(0);
            options.setAllowRecursiveKeys(false);
            options.setCodePointLimit(64 * 1024);
            Object loaded = new Yaml(new SafeConstructor(options)).load(text);
            if (!(loaded instanceof Map<?, ?> root)) return List.of();
            Object dependenciesRaw = root.get("dependencies");
            if (!(dependenciesRaw instanceof Map<?, ?> dependencies)) return List.of();
            Object toolsRaw = dependencies.get("tools");
            if (!(toolsRaw instanceof List<?> tools)) return List.of();
            List<SkillDependencyHint> result = new ArrayList<>();
            for (Object raw : tools.stream().limit(64).toList()) {
                if (!(raw instanceof Map<?, ?> tool)) continue;
                Object typeRaw = tool.get("type");
                Object valueRaw = tool.containsKey("value") ? tool.get("value") : tool.get("name");
                if (!(typeRaw instanceof String type) || !(valueRaw instanceof String value)
                        || type.length() > 32 || value.length() > 256) continue;
                DependencyStatus status = "mcp".equalsIgnoreCase(type) ? dependencyResolver.apply(value) : DependencyStatus.UNKNOWN;
                result.add(new SkillDependencyHint(type, value, status));
            }
            return List.copyOf(result);
        } catch (SkillException exception) {
            if ("SKILL_SECRET_DETECTED".equals(exception.code())) throw exception;
            return List.of();
        } catch (Exception exception) {
            return List.of();
        }
    }

    private List<SkillResourceDescriptor> descriptors(String sessionId, ValidatedSkillPackage validated) {
        return validated.resources().stream().map(resource -> new SkillResourceDescriptor(
                stableId("res", sessionId + "|" + resource.relativePath()), resource.relativePath(), resource.kind(),
                resource.mediaType(), resource.byteSize(), resource.contentHash(), resource.executableCandidate())).toList();
    }

    private void enforceImportLimits(ValidatedSkillPackage validated) {
        if (validated.totalBytes() > DefaultSkillPackageValidator.MAX_PACKAGE_BYTES
                || validated.resources().size() > DefaultSkillPackageValidator.MAX_RESOURCES
                || validated.resources().stream().anyMatch(item -> item.byteSize() > DefaultSkillPackageValidator.MAX_RESOURCE_BYTES)) {
            throw new SkillImportException("SKILL_IMPORT_PACKAGE_TOO_LARGE");
        }
    }

    private ImportSession requireSession(String id) {
        if (id == null || !id.matches("imp_[A-Za-z0-9_-]{20,80}")) throw new SkillImportException("SKILL_IMPORT_EXPIRED");
        ImportSession session = sessions.get(id);
        if (session == null) throw new SkillImportException("SKILL_IMPORT_EXPIRED");
        return session;
    }

    private void reserveSessionSlot() {
        if (!sessionPermits.tryAcquire()) throw new SkillImportException("SKILL_IMPORT_BUSY");
    }

    private void expireSessions() {
        Instant now = clock.instant();
        sessions.values().stream().filter(item -> now.isAfter(item.expiresAt())).map(ImportSession::id).toList().forEach(this::cancel);
    }

    private void enforceStagingBudget(ImportSession newlyAdded) {
        long size = sessions.values().stream().mapToLong(item -> item.validated().totalBytes()).sum();
        if (size > MAX_STAGING_BYTES) {
            throw new SkillImportException("SKILL_IMPORT_BUSY");
        }
    }

    private void discardFailedInspection(String sessionId, Path sessionRoot) {
        sessions.remove(sessionId);
        sessionPermits.release();
        safeDelete(sessionRoot, stagingRoot);
    }

    private void cleanupOrphanStaging() {
        if (!Files.isDirectory(stagingRoot, NOFOLLOW_LINKS)) return;
        Instant cutoff = clock.instant().minus(ORPHAN_TTL);
        try (Stream<Path> stream = Files.list(stagingRoot)) {
            for (Path child : stream.toList()) {
                if (sessions.containsKey(child.getFileName().toString())) continue;
                try {
                    if (Files.getLastModifiedTime(child, NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) safeDelete(child, stagingRoot);
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    private SkillSummary findUserSkill(String name) {
        return registry.list(new SkillQuery(SkillScope.USER, null)).items().stream()
                .filter(item -> item.name().equals(name)).findFirst()
                .orElseThrow(() -> new SkillImportException("SKILL_INSTALL_ROLLBACK_FAILED"));
    }

    private SkillInstallResult result(SkillSummary summary, String operation, ValidatedSkillPackage validated,
                                      boolean managed, Instant installedAt) {
        boolean rollback = repository.findByName(summary.name()).map(ManagedInstall::backupRevisionId).isPresent();
        return new SkillInstallResult(summary.id(), summary.name(), operation, validated.packageHash(),
                validated.skillDocumentHash(), summary.enabled(), managed, rollback, installedAt);
    }

    private void createManagedDirectories() {
        try {
            Files.createDirectories(userRoot);
            Files.createDirectories(stagingRoot);
            Files.createDirectories(workRoot);
            Files.createDirectories(backupRoot);
            Files.createDirectories(quarantineRoot);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot initialize Skill import directories", exception);
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new SkillImportException("SKILL_INSTALL_ATOMIC_MOVE_UNAVAILABLE", exception);
        }
    }

    private static String copyAndHash(Path source, Path target) throws IOException {
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
        try (InputStream input = Files.newInputStream(source); var output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String hashFile(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new SkillImportException("SKILL_IMPORT_SOURCE_CHANGED", exception);
        }
    }

    private static void safeDelete(Path target, Path allowedRoot) {
        Path normalized = normalize(target);
        Path root = normalize(allowedRoot);
        if (normalized.equals(root) || !normalized.startsWith(root)) throw new IllegalArgumentException("Unsafe managed path");
        if (!Files.exists(normalized, NOFOLLOW_LINKS)) return;
        try (Stream<Path> stream = Files.walk(normalized)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { }
    }

    private static boolean isLinkLike(Path path) {
        if (Files.isSymbolicLink(path)) return true;
        try {
            return !path.toRealPath(NOFOLLOW_LINKS).equals(path.toRealPath());
        } catch (IOException exception) {
            throw new SkillImportException("SKILL_IMPORT_UNSUPPORTED_ENTRY", exception);
        }
    }

    private static String stableId(String prefix, String value) {
        return prefix + "_" + hashText(value).substring(0, 24);
    }

    private static String hashText(String value) {
        return DefaultSkillPackageValidator.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(nullToEmpty(left).getBytes(StandardCharsets.US_ASCII),
                nullToEmpty(right).getBytes(StandardCharsets.US_ASCII));
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static Path normalize(Path value) { return Objects.requireNonNull(value).toAbsolutePath().normalize(); }

    private static SkillImportException invalidPackage(SkillException exception) {
        String code = "SKILL_SECRET_DETECTED".equals(exception.code())
                ? "SKILL_IMPORT_SECRET_DETECTED" : "SKILL_IMPORT_INVALID_SKILL";
        return new SkillImportException(code, exception.code(), exception);
    }

    private static RuntimeException translateInstall(Exception exception) {
        if (exception instanceof SkillImportException importException) return importException;
        return new SkillImportException("SKILL_INSTALL_ROLLBACK_FAILED", exception);
    }

    private static String errorCode(Exception exception) {
        return exception instanceof SkillImportException importException ? importException.code() : "SKILL_INSTALL_ROLLBACK_FAILED";
    }

    private record ImportSession(String id, Path sessionRoot, Path stagedSkillRoot,
                                 ImportSourceSummary source, ValidatedSkillPackage validated, Instant expiresAt) {}
    private record ConflictState(InstallConflict conflict, String existingPackageHash,
                                 String existingContentHash, List<String> warningCodes) {}
}
