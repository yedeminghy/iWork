package dev.agentdesktop.runtime.skill.importing;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.persistence.LocalDatabase;
import dev.agentdesktop.persistence.SkillInstallRepository;
import dev.agentdesktop.runtime.skill.LocalSkillRegistry;
import dev.agentdesktop.runtime.skill.SkillModels.SkillQuery;
import dev.agentdesktop.runtime.skill.SkillModels.SkillScope;
import dev.agentdesktop.runtime.skill.SkillModels.SkillStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static dev.agentdesktop.runtime.skill.importing.SkillImportModels.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultSkillImportServiceTest {
    @TempDir Path temp;

    @Test
    void inspectsInstallsReplacesRollsBackAndRemovesLocalSkill() throws Exception {
        Fixture fixture = fixture();
        Path source = writeSkill(temp.resolve("source"), "managed-skill", "VERSION_ONE", true);

        SkillImportManifest first = fixture.service.inspectLocal(source);
        assertEquals(InstallConflict.NONE, first.conflict());
        assertEquals(ImportSourceType.LOCAL_DIRECTORY, first.source().type());
        assertNull(first.source().repositoryUrl());
        assertTrue(first.resources().stream().anyMatch(item -> item.executableCandidate()));
        SkillInstallResult installed = fixture.service.commit(first.sessionId(), InstallAction.INSTALL, null);
        assertFalse(installed.enabled());
        assertTrue(installed.managed());
        assertEquals("VERSION_ONE", Files.readString(fixture.appHome.resolve("skills/managed-skill/SKILL.md"))
                .lines().reduce((left, right) -> right).orElseThrow());
        assertFalse(fixture.registry.get(installed.skillId(), false).enabled());

        Files.writeString(source.resolve("SKILL.md"), document("managed-skill", "VERSION_TWO"), StandardCharsets.UTF_8);
        SkillImportManifest second = fixture.service.inspectLocal(source);
        assertEquals(InstallConflict.REPLACE_REQUIRED, second.conflict());
        SkillInstallResult replaced = fixture.service.commit(second.sessionId(), InstallAction.REPLACE,
                second.expectedExistingContentHash());
        assertTrue(replaced.rollbackAvailable());
        assertFalse(replaced.enabled());
        assertTrue(Files.readString(fixture.appHome.resolve("skills/managed-skill/SKILL.md")).contains("VERSION_TWO"));

        SkillInstallResult rolledBack = fixture.service.rollback(replaced.skillId());
        assertFalse(rolledBack.enabled());
        assertTrue(Files.readString(fixture.appHome.resolve("skills/managed-skill/SKILL.md")).contains("VERSION_ONE"));

        fixture.service.remove(rolledBack.skillId());
        assertFalse(Files.exists(fixture.appHome.resolve("skills/managed-skill")));
        assertEquals(SkillStatus.MISSING, fixture.registry.get(rolledBack.skillId(), false).status());
        assertTrue(Files.exists(source));
    }

    @Test
    void identicalPackageIsNoOpAndSecretResourceIsRejected() throws Exception {
        Fixture fixture = fixture();
        Path source = writeSkill(temp.resolve("source-identical"), "same-skill", "BODY", false);
        SkillImportManifest first = fixture.service.inspectLocal(source);
        fixture.service.commit(first.sessionId(), InstallAction.INSTALL, null);

        SkillImportManifest identical = fixture.service.inspectLocal(source);
        assertEquals(InstallConflict.IDENTICAL, identical.conflict());
        assertEquals("NO_OP", fixture.service.commit(identical.sessionId(), InstallAction.NO_OP, null).operation());

        Path malicious = writeSkill(temp.resolve("source-secret"), "secret-skill", "BODY", false);
        Files.createDirectories(malicious.resolve("references"));
        Files.writeString(malicious.resolve("references/config.txt"), "api_key = abcdefghijklmnopqrstuvwxyz123456");
        SkillImportException exception = assertThrows(SkillImportException.class, () -> fixture.service.inspectLocal(malicious));
        assertEquals("SKILL_IMPORT_SECRET_DETECTED", exception.code());
        assertFalse(Files.exists(fixture.appHome.resolve("skills/secret-skill")));
    }

    @Test
    void acceptsNestedOfficeSchemasButStillRejectsExcessiveDepth() throws Exception {
        Fixture fixture = fixture();
        Path supported = writeSkill(temp.resolve("source-office"), "office-skill", "BODY", false);
        Path schema = supported.resolve("scripts/office/schemas/ecma/fourth-edition/schema.xsd");
        Files.createDirectories(schema.getParent());
        Files.writeString(schema, "<schema/>", StandardCharsets.UTF_8);

        SkillImportManifest manifest = fixture.service.inspectLocal(supported);
        assertTrue(manifest.resources().stream().anyMatch(item ->
                item.relativePath().equals("scripts/office/schemas/ecma/fourth-edition/schema.xsd")));
        fixture.service.cancel(manifest.sessionId());

        Path excessive = writeSkill(temp.resolve("source-deep"), "too-deep-skill", "BODY", false);
        Path resource = excessive.resolve("references");
        for (int index = 0; index < dev.agentdesktop.runtime.skill.DefaultSkillPackageValidator.MAX_DEPTH; index++) {
            resource = resource.resolve("level-" + index);
        }
        resource = resource.resolve("too-deep.md");
        Files.createDirectories(resource.getParent());
        Files.writeString(resource, "DEEP", StandardCharsets.UTF_8);
        SkillImportException error = assertThrows(SkillImportException.class, () -> fixture.service.inspectLocal(excessive));
        assertEquals("SKILL_IMPORT_PACKAGE_TOO_LARGE", error.code());
    }

    @Test
    void refusesToRemoveUnmanagedUserSkill() throws Exception {
        Fixture fixture = fixture();
        writeSkill(fixture.appHome.resolve("skills"), "manual-skill", "MANUAL", false);
        fixture.registry.refresh(SkillScope.USER, "ws_default");
        String id = fixture.registry.list(new SkillQuery(SkillScope.USER, null)).items().getFirst().id();
        SkillImportException exception = assertThrows(SkillImportException.class, () -> fixture.service.remove(id));
        assertEquals("SKILL_INSTALL_NOT_MANAGED", exception.code());
        assertTrue(Files.exists(fixture.appHome.resolve("skills/manual-skill")));
    }

    @Test
    void boundsConcurrentInspectionSessionsAndReleasesCancelledSlot() throws Exception {
        Fixture fixture = fixture();
        SkillImportManifest first = fixture.service.inspectLocal(writeSkill(temp.resolve("source-a"), "session-a", "A", false));
        SkillImportManifest second = fixture.service.inspectLocal(writeSkill(temp.resolve("source-b"), "session-b", "B", false));
        SkillImportManifest third = fixture.service.inspectLocal(writeSkill(temp.resolve("source-c"), "session-c", "C", false));
        SkillImportException busy = assertThrows(SkillImportException.class, () -> fixture.service.inspectLocal(
                writeSkill(temp.resolve("source-d"), "session-d", "D", false)));
        assertEquals("SKILL_IMPORT_BUSY", busy.code());
        fixture.service.cancel(second.sessionId());
        SkillImportManifest replacement = fixture.service.inspectLocal(temp.resolve("source-d/session-d"));
        assertEquals("session-d", replacement.name());
        fixture.service.cancel(first.sessionId());fixture.service.cancel(third.sessionId());fixture.service.cancel(replacement.sessionId());
    }

    private Fixture fixture() throws Exception {
        Path appHome = temp.resolve("app-home");
        Path builtin = temp.resolve("builtin");
        Path workspace = appHome.resolve("workspace");
        Files.createDirectories(builtin);
        Files.createDirectories(workspace.resolve(".agent-desktop/skills"));
        LocalDatabase database = new LocalDatabase(appHome);
        database.migrate();
        ObjectMapper mapper = new ObjectMapper();
        LocalSkillRegistry registry = new LocalSkillRegistry(database, mapper, builtin, appHome, workspace, "ws_default");
        registry.refreshAll();
        SkillInstallRepository repository = new SkillInstallRepository(database);
        GitHubSkillFetcher unsupported = (source, staging) -> { throw new SkillImportException("SKILL_IMPORT_NETWORK_FAILED"); };
        DefaultSkillImportService service = new DefaultSkillImportService(appHome, mapper, registry, repository,
                unsupported, value -> DependencyStatus.UNAVAILABLE);
        return new Fixture(appHome, registry, service);
    }

    private static Path writeSkill(Path root, String name, String body, boolean script) throws Exception {
        Path skill = root.resolve(name);
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), document(name, body), StandardCharsets.UTF_8);
        if (script) {
            Files.createDirectories(skill.resolve("scripts"));
            Files.writeString(skill.resolve("scripts/run.js"), "throw new Error('never execute')", StandardCharsets.UTF_8);
        }
        return skill;
    }

    private static String document(String name, String body) {
        return "---\nname: " + name + "\ndescription: safely test managed imports\n---\n" + body + "\n";
    }

    private record Fixture(Path appHome, LocalSkillRegistry registry, DefaultSkillImportService service) {}
}
