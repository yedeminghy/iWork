package dev.agentdesktop.runtime.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.persistence.ConversationRepository;
import dev.agentdesktop.persistence.ConversationStore;
import dev.agentdesktop.persistence.LocalDatabase;
import dev.agentdesktop.runtime.skill.SkillModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LocalSkillRegistryTest {
    @TempDir Path temp;

    @Test
    void discoversScopesShadowsAndLoadsProgressively() throws Exception {
        Fixture fixture=fixture();
        writeSkill(fixture.builtin,"summary-skill","builtin description","BUILTIN_BODY",null);
        writeSkill(fixture.user,"summary-skill","user description","USER_BODY",null);
        Path workspaceSkill=writeSkill(fixture.workspaceSkills,"summary-skill","workspace description","WORKSPACE_BODY","references/format.md");
        Files.createDirectories(workspaceSkill.resolve("references"));
        Files.writeString(workspaceSkill.resolve("references/format.md"),"RESOURCE_MARKER",StandardCharsets.UTF_8);

        fixture.registry.refreshAll();
        SkillPage page=fixture.registry.list(new SkillQuery(null,null));
        assertEquals(3,page.items().size());
        SkillSummary winner=page.items().stream().filter(item->item.scope()==SkillScope.WORKSPACE).findFirst().orElseThrow();
        assertEquals(SkillStatus.VALID,winner.status());
        assertEquals(2,page.items().stream().filter(item->item.status()==SkillStatus.SHADOWED).count());
        SkillCatalogSnapshot snapshot=fixture.registry.snapshot("ws_default");
        assertEquals(List.of(winner.id()),snapshot.skills().stream().map(SkillMetadata::id).toList());
        assertFalse(snapshot.toString().contains("WORKSPACE_BODY"));
        assertFalse(snapshot.toString().contains("RESOURCE_MARKER"));
        assertFalse(snapshot.toString().contains("shell.execute"));

        startTurn(fixture,"trn_12345678");
        DefaultSkillRuntimeBridge bridge=new DefaultSkillRuntimeBridge(fixture.registry);
        bridge.prepare("trn_12345678","ws_default",List.of(winner.id()));
        ActivatedSkill activated=bridge.activated("trn_12345678").getFirst();
        assertTrue(activated.instructions().contains("WORKSPACE_BODY"));
        assertFalse(activated.toString().contains("RESOURCE_MARKER"));
        SkillResourceDescriptor resource=activated.resources().stream().filter(item->item.relativePath().equals("references/format.md")).findFirst().orElseThrow();
        assertEquals("RESOURCE_MARKER",bridge.readResource("trn_12345678",winner.id(),resource.id()).content());
    }

    @Test
    void preservesDisabledStateAndRejectsStaleContent() throws Exception {
        Fixture fixture=fixture();
        Path skill=writeSkill(fixture.user,"review-skill","review code when requested","ORIGINAL_BODY","scripts/run.js");
        Files.createDirectories(skill.resolve("scripts"));
        Files.writeString(skill.resolve("scripts/run.js"),"throw new Error('must never execute')",StandardCharsets.UTF_8);
        fixture.registry.refreshAll();
        SkillSummary item=fixture.registry.snapshot("ws_default").skills().stream().map(meta->fixture.registry.list(new SkillQuery(null,null)).items().stream().filter(row->row.id().equals(meta.id())).findFirst().orElseThrow()).findFirst().orElseThrow();
        assertTrue(fixture.registry.listResources(item.id()).getFirst().executableCandidate());
        fixture.registry.setEnabled(item.id(),false);
        fixture.registry.refresh(SkillScope.USER,"ws_default");
        assertFalse(fixture.registry.get(item.id(),false).enabled());
        fixture.registry.setEnabled(item.id(),true);

        startTurn(fixture,"trn_abcdefgh");
        DefaultSkillRuntimeBridge bridge=new DefaultSkillRuntimeBridge(fixture.registry);
        bridge.prepare("trn_abcdefgh","ws_default",List.of());
        Files.writeString(skill.resolve("SKILL.md"),"---\nname: review-skill\ndescription: review code when requested\n---\nCHANGED_BODY\n",StandardCharsets.UTF_8);
        SkillException stale=assertThrows(SkillException.class,()->bridge.activate("trn_abcdefgh",item.id(),ActivationReason.IMPLICIT));
        assertEquals("SKILL_STALE",stale.code());
    }

    @Test
    void rejectsPptxActivationWhenManagedRuntimeIsUnavailable() throws Exception {
        Fixture fixture=fixture();
        writeSkill(fixture.user,"pptx","create PowerPoint presentations","BODY",null);
        fixture.registry.refreshAll();
        SkillSummary item=find(fixture.registry.list(new SkillQuery(SkillScope.USER,SkillStatus.VALID)),"pptx");
        startTurn(fixture,"trn_pptxrun1");
        DefaultSkillRuntimeBridge bridge=new DefaultSkillRuntimeBridge(fixture.registry,capability->false);
        SkillException unavailable=assertThrows(SkillException.class,()->bridge.prepare("trn_pptxrun1","ws_default",List.of(item.id())));
        assertEquals("PPTX_RUNTIME_UNAVAILABLE",unavailable.code());
    }

    @Test
    void appendsManagedPolicyToLegacyPptxInstructions() throws Exception {
        Fixture fixture=fixture();
        writeSkill(fixture.user,"pptx","create PowerPoint presentations","If require fails, run npm install and use python-pptx.",null);
        fixture.registry.refreshAll();
        SkillSummary item=find(fixture.registry.list(new SkillQuery(SkillScope.USER,SkillStatus.VALID)),"pptx");
        startTurn(fixture,"trn_pptxrun2");
        DefaultSkillRuntimeBridge bridge=new DefaultSkillRuntimeBridge(fixture.registry,capability->true);
        bridge.prepare("trn_pptxrun2","ws_default",List.of(item.id()));
        String instructions=bridge.activated("trn_pptxrun2").getFirst().instructions();
        assertTrue(instructions.contains("Application-managed PPT runtime policy"));
        assertTrue(instructions.contains("Never fall back to python-pptx"));
    }

    @Test
    void invalidHigherPrioritySkillBlocksFallbackAndSecretsAreDiagnosed() throws Exception {
        Fixture fixture=fixture();
        writeSkill(fixture.builtin,"guarded-skill","safe builtin description","SAFE_BODY",null);
        Path malicious=fixture.workspaceSkills.resolve("guarded-skill");Files.createDirectories(malicious);
        Files.writeString(malicious.resolve("SKILL.md"),"---\nname: guarded-skill\ndescription: unsafe replacement\n---\napi_key = abcdefghijklmnopqrstuvwxyz123456\n",StandardCharsets.UTF_8);
        fixture.registry.refreshAll();
        SkillPage page=fixture.registry.list(new SkillQuery(null,null));
        SkillSummary high=page.items().stream().filter(item->item.scope()==SkillScope.WORKSPACE).findFirst().orElseThrow();
        SkillSummary low=page.items().stream().filter(item->item.scope()==SkillScope.BUILTIN).findFirst().orElseThrow();
        assertEquals(SkillStatus.INVALID,high.status());
        assertEquals("SKILL_SECRET_DETECTED",high.lastErrorCode());
        assertEquals(SkillStatus.SHADOWED,low.status());
        assertTrue(fixture.registry.snapshot("ws_default").skills().isEmpty());
        assertNull(fixture.registry.get(high.id(),true).instructions());
    }

    @Test
    void binaryResourceNeverBecomesTextContext() throws Exception {
        Fixture fixture=fixture();
        Path skill=writeSkill(fixture.user,"binary-skill","inspect binary fixtures","BODY","assets/data.bin");
        Files.createDirectories(skill.resolve("assets"));Files.write(skill.resolve("assets/data.bin"),new byte[]{0,1,2,(byte)0xff});
        fixture.registry.refreshAll();
        SkillSummary item=fixture.registry.list(new SkillQuery(null,SkillStatus.VALID)).items().getFirst();
        SkillResourceDescriptor resource=fixture.registry.listResources(item.id()).getFirst();
        assertTrue(fixture.registry.readResourceForView(item.id(),resource.id()).binary());
        startTurn(fixture,"trn_binary12");
        DefaultSkillRuntimeBridge bridge=new DefaultSkillRuntimeBridge(fixture.registry);
        bridge.prepare("trn_binary12","ws_default",List.of(item.id()));
        SkillException binary=assertThrows(SkillException.class,()->bridge.readResource("trn_binary12",item.id(),resource.id()));
        assertEquals("SKILL_RESOURCE_BINARY",binary.code());
    }

    @Test
    void diagnosesInvalidNamesYamlRequiredFieldsAndOversizedDocuments() throws Exception {
        Fixture fixture=fixture();
        writeRawSkill(fixture.user,"Bad_Name","---\nname: Bad_Name\ndescription: bad name\n---\nBODY\n");
        writeRawSkill(fixture.user,"directory-name","---\nname: other-name\ndescription: mismatch\n---\nBODY\n");
        writeRawSkill(fixture.user,"empty-description","---\nname: empty-description\ndescription: ''\n---\nBODY\n");
        writeRawSkill(fixture.user,"unsafe-yaml","---\nname: unsafe-yaml\ndescription: !!javax.script.ScriptEngineManager []\n---\nBODY\n");
        Path oversized=fixture.user.resolve("oversized-skill");Files.createDirectories(oversized);
        Files.writeString(oversized.resolve("SKILL.md"),"---\nname: oversized-skill\ndescription: too large\n---\n"+"x".repeat(LocalSkillRegistry.MAX_SKILL_BYTES),StandardCharsets.UTF_8);

        fixture.registry.refreshAll();
        SkillPage page=fixture.registry.list(new SkillQuery(SkillScope.USER,SkillStatus.INVALID));
        assertEquals(5,page.items().size());
        assertEquals("SKILL_NAME_INVALID",find(page,"Bad_Name").lastErrorCode());
        assertEquals("SKILL_NAME_INVALID",find(page,"directory-name").lastErrorCode());
        assertEquals("SKILL_INVALID_FRONTMATTER",find(page,"empty-description").lastErrorCode());
        assertEquals("SKILL_INVALID_FRONTMATTER",find(page,"unsafe-yaml").lastErrorCode());
        assertEquals("SKILL_INVALID_FRONTMATTER",find(page,"oversized-skill").lastErrorCode());
    }

    @Test
    void rejectsTraversalDeepAndOversizedResourcesDeterministically() throws Exception {
        Fixture fixture=fixture();
        Path escaped=writeSkill(fixture.user,"escaped-resource","inspect safe references","BODY","references/good.md");
        Files.createDirectories(escaped.resolve("references"));Files.writeString(escaped.resolve("references/good.md"),"GOOD",StandardCharsets.UTF_8);
        Path large=writeSkill(fixture.user,"large-resource","inspect a large reference","BODY","references/large.md");
        Files.createDirectories(large.resolve("references"));Files.write(large.resolve("references/large.md"),new byte[LocalSkillRegistry.MAX_RESOURCE_BYTES+1]);
        Path deep=writeSkill(fixture.user,"deep-resource","inspect nested references","BODY",null);
        Path deepFile=deep.resolve("references");
        for(int index=0;index<LocalSkillRegistry.MAX_DEPTH;index++)deepFile=deepFile.resolve("level-"+index);
        deepFile=deepFile.resolve("too-deep.md");Files.createDirectories(deepFile.getParent());Files.writeString(deepFile,"DEEP",StandardCharsets.UTF_8);
        Files.writeString(fixture.user.resolve("outside.txt"),"OUTSIDE",StandardCharsets.UTF_8);

        fixture.registry.refreshAll();
        SkillPage all=fixture.registry.list(new SkillQuery(SkillScope.USER,null));
        assertEquals("SKILL_RESOURCE_OUT_OF_SCOPE",find(all,"deep-resource").lastErrorCode());
        SkillSummary largeEntry=find(all,"large-resource");
        SkillResourceDescriptor largeResource=fixture.registry.listResources(largeEntry.id()).getFirst();
        assertEquals("SKILL_RESOURCE_TOO_LARGE",assertThrows(SkillException.class,()->fixture.registry.readResourceForView(largeEntry.id(),largeResource.id())).code());

        SkillSummary escapedEntry=find(all,"escaped-resource");
        SkillResourceDescriptor descriptor=fixture.registry.listResources(escapedEntry.id()).getFirst();
        try(var connection=fixture.database.open(); PreparedStatement update=connection.prepareStatement("UPDATE skill_resource SET relative_path='../outside.txt' WHERE id=?")){
            update.setString(1,descriptor.id());assertEquals(1,update.executeUpdate());
        }
        assertEquals("SKILL_RESOURCE_OUT_OF_SCOPE",assertThrows(SkillException.class,()->fixture.registry.readResourceForView(escapedEntry.id(),descriptor.id())).code());
    }

    @Test
    void rejectsDirectoryLinksAndWindowsReservedNames() throws Exception {
        assertFalse(LocalSkillRegistry.validSkillName("con"));
        assertFalse(LocalSkillRegistry.validSkillName("COM1"));
        assertFalse(LocalSkillRegistry.validSkillName("lpt9"));
        assertTrue(LocalSkillRegistry.validSkillName("console-helper"));

        Fixture fixture=fixture();
        Path outside=writeSkill(temp.resolve("outside-skills"),"linked-skill","outside linked Skill","BODY",null);
        createDirectoryLink(fixture.user.resolve("linked-skill"),outside);
        fixture.registry.refreshAll();
        SkillSummary linked=find(fixture.registry.list(new SkillQuery(SkillScope.USER,null)),"linked-skill");
        assertEquals(SkillStatus.INVALID,linked.status());
        assertEquals("SKILL_RESOURCE_OUT_OF_SCOPE",linked.lastErrorCode());
    }

    @Test
    void enforcesActivationInstructionAndFrozenGenerationBudgets() throws Exception {
        Fixture fixture=fixture();List<String> ids=new ArrayList<>();
        for(int index=0;index<6;index++)writeSkill(fixture.user,"budget-skill-"+index,"budget helper number "+index,"BODY",null);
        Path large=writeSkill(fixture.user,"large-instruction","load a deliberately large instruction","x".repeat(90_000),null);
        fixture.registry.refreshAll();
        fixture.registry.list(new SkillQuery(SkillScope.USER,SkillStatus.VALID)).items().stream().filter(item->item.name().startsWith("budget-skill-")).forEach(item->ids.add(item.id()));
        startTurn(fixture,"trn_budget123");DefaultSkillRuntimeBridge bridge=new DefaultSkillRuntimeBridge(fixture.registry);
        assertEquals("SKILL_CONTEXT_BUDGET_EXCEEDED",assertThrows(SkillException.class,()->bridge.prepare("trn_budget123","ws_default",ids)).code());

        startTurn(fixture,"trn_large1234");bridge.prepare("trn_large1234","ws_default",List.of());
        SkillSummary largeEntry=find(fixture.registry.list(new SkillQuery(SkillScope.USER,null)),"large-instruction");
        assertEquals("SKILL_CONTEXT_BUDGET_EXCEEDED",assertThrows(SkillException.class,()->bridge.activate("trn_large1234",largeEntry.id(),ActivationReason.IMPLICIT)).code());

        startTurn(fixture,"trn_frozen123");SkillTurnContext frozen=bridge.prepare("trn_frozen123","ws_default",List.of());
        writeSkill(fixture.user,"late-skill","created after the turn snapshot","LATE",null);fixture.registry.refresh(SkillScope.USER,"ws_default");
        SkillSummary late=find(fixture.registry.list(new SkillQuery(SkillScope.USER,null)),"late-skill");
        assertTrue(frozen.catalog().stream().noneMatch(item->item.id().equals(late.id())));
        assertEquals("SKILL_NOT_FOUND",assertThrows(SkillException.class,()->bridge.activate("trn_frozen123",late.id(),ActivationReason.IMPLICIT)).code());
    }

    @Test
    void enforcesAggregateResourceBudgetAcrossFiveActivatedSkills() throws Exception {
        Fixture fixture=fixture();
        for(int index=0;index<5;index++){
            Path skill=writeSkill(fixture.user,"resource-budget-"+index,"load resource budget "+index,"BODY","references/data-"+index+".txt");
            Files.createDirectories(skill.resolve("references"));byte[] data=new byte[230_000];java.util.Arrays.fill(data,(byte)'a');Files.write(skill.resolve("references/data-"+index+".txt"),data);
        }
        fixture.registry.refreshAll();List<SkillSummary> skills=fixture.registry.list(new SkillQuery(SkillScope.USER,SkillStatus.VALID)).items();
        startTurn(fixture,"trn_resources1");DefaultSkillRuntimeBridge bridge=new DefaultSkillRuntimeBridge(fixture.registry);
        bridge.prepare("trn_resources1","ws_default",skills.stream().map(SkillSummary::id).toList());
        for(int index=0;index<4;index++){
            SkillSummary skill=skills.get(index);SkillResourceDescriptor resource=fixture.registry.listResources(skill.id()).getFirst();
            assertEquals(230_000,bridge.readResource("trn_resources1",skill.id(),resource.id()).byteSize());
        }
        SkillSummary fifth=skills.get(4);SkillResourceDescriptor resource=fixture.registry.listResources(fifth.id()).getFirst();
        assertEquals("SKILL_CONTEXT_BUDGET_EXCEEDED",assertThrows(SkillException.class,()->bridge.readResource("trn_resources1",fifth.id(),resource.id())).code());
    }

    @Test
    void preservesLastGenerationOnSourceFailureAndMarksRemovedEntryMissing() throws Exception {
        Fixture fixture=fixture();Path skill=writeSkill(fixture.user,"durable-skill","survive failed refresh","BODY",null);fixture.registry.refreshAll();
        SkillSummary original=find(fixture.registry.list(new SkillQuery(SkillScope.USER,null)),"durable-skill");
        Path backup=fixture.user.resolveSibling("skills-backup");Files.move(fixture.user,backup);Files.writeString(fixture.user,"not a directory",StandardCharsets.UTF_8);
        assertEquals("SKILL_SOURCE_UNAVAILABLE",assertThrows(SkillException.class,()->fixture.registry.refresh(SkillScope.USER,"ws_default")).code());
        assertEquals(SkillStatus.VALID,fixture.registry.get(original.id(),false).status());

        Files.delete(fixture.user);Files.move(backup,fixture.user);Files.delete(skill.resolve("SKILL.md"));Files.delete(skill);
        fixture.registry.refresh(SkillScope.USER,"ws_default");
        assertEquals(SkillStatus.MISSING,fixture.registry.get(original.id(),false).status());
    }

    private Fixture fixture() throws Exception {
        Path appHome=temp.resolve("app-home");Path workspace=appHome.resolve("workspace");Path builtin=temp.resolve("builtin");Path user=appHome.resolve("skills");Path workspaceSkills=workspace.resolve(".agent-desktop").resolve("skills");
        Files.createDirectories(builtin);Files.createDirectories(user);Files.createDirectories(workspaceSkills);
        LocalDatabase database=new LocalDatabase(appHome);database.migrate();ObjectMapper mapper=new ObjectMapper();
        ConversationRepository conversations=new ConversationRepository(database,mapper);conversations.initializeProfile(workspace,"Test User");
        return new Fixture(database,mapper,conversations,new LocalSkillRegistry(database,mapper,builtin,appHome,workspace,"ws_default"),builtin,user,workspaceSkills);
    }
    private static Path writeSkill(Path root,String name,String description,String body,String mentionedResource)throws Exception{Path dir=root.resolve(name);Files.createDirectories(dir);String mention=mentionedResource==null?"":"\nUse "+mentionedResource;Files.writeString(dir.resolve("SKILL.md"),"---\nname: "+name+"\ndescription: "+description+"\nallowed-tools: shell.execute\n---\n"+body+mention+"\n",StandardCharsets.UTF_8);return dir;}
    private static Path writeRawSkill(Path root,String directory,String document)throws Exception{Path dir=root.resolve(directory);Files.createDirectories(dir);Files.writeString(dir.resolve("SKILL.md"),document,StandardCharsets.UTF_8);return dir;}
    private static void createDirectoryLink(Path link,Path target)throws Exception{
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")){
            Process process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(10,TimeUnit.SECONDS),"junction creation timed out");
            assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
        }else Files.createSymbolicLink(link,target);
    }
    private static SkillSummary find(SkillPage page,String name){return page.items().stream().filter(item->item.name().equals(name)).findFirst().orElseThrow();}
    private static void startTurn(Fixture fixture,String turnId){String suffix=turnId.substring(4);fixture.conversations.startTurn(new ConversationStore.StartTurnCommand("thr_"+suffix,turnId,"run_"+suffix,"mock","itm_"+suffix,"hello",Instant.now()));}
    private record Fixture(LocalDatabase database,ObjectMapper mapper,ConversationRepository conversations,LocalSkillRegistry registry,Path builtin,Path user,Path workspaceSkills){}
}
