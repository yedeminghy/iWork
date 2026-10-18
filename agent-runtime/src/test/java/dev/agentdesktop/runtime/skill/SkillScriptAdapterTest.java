package dev.agentdesktop.runtime.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.persistence.ConversationRepository;
import dev.agentdesktop.persistence.ConversationStore;
import dev.agentdesktop.persistence.LocalDatabase;
import dev.agentdesktop.persistence.PermissionRepository;
import dev.agentdesktop.persistence.PermissionStore;
import dev.agentdesktop.runtime.permission.DefaultPermissionEngine;
import dev.agentdesktop.runtime.permission.PermissionException;
import dev.agentdesktop.runtime.permission.PermissionFingerprints;
import dev.agentdesktop.runtime.permission.PermissionModels.*;
import dev.agentdesktop.runtime.workspace.FileRootGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SkillScriptAdapterTest {
    private static final Duration TEST_SCRIPT_TIMEOUT=Duration.ofSeconds(120);
    @Test
    void executesOnlyActivatedHashPinnedPowerShellPlan(@TempDir Path temp) throws Exception {
        Fixture fixture=fixture(temp,"Set-Content -LiteralPath 'week6-marker.txt' -Value $args[0]\nWrite-Output 'SAFE_SUMMARY'\n");
        String turnId="trn_script123";startTurn(fixture,turnId);fixture.bridge.prepare(turnId,"ws_default",List.of(fixture.skillId));
        SkillScriptAdapter.PreparedScript prepared=fixture.adapter.prepare(turnId,fixture.skillId,fixture.resourceId,"root_test",List.of("approved"));
        String fingerprint="script-invocation";
        ExecutionPermit permit=new DefaultPermissionEngine(false).allowOnce(new ResolvedApproval("apr_script",ApprovalDecision.ALLOW_ONCE,request(turnId,fingerprint)));

        SkillScriptAdapter.ScriptResult result=fixture.adapter.execute(prepared,permit,fingerprint,()->false);

        assertTrue(result.succeeded());
        assertEquals("SAFE_SUMMARY",result.summary());
        assertEquals("approved",Files.readString(fixture.workspace.resolve("week6-marker.txt")).strip());
        assertEquals(0,fixture.adapter.activeProcessCount());
        assertEquals("PERMISSION_PERMIT_CONSUMED",assertThrows(PermissionException.class,()->fixture.adapter.execute(prepared,permit,fingerprint,()->false)).code());
    }

    @Test
    void rejectsUnactivatedAndStaleScripts(@TempDir Path temp) throws Exception {
        Fixture fixture=fixture(temp,"Write-Output 'ORIGINAL'\n");
        String turnId="trn_stale1234";startTurn(fixture,turnId);fixture.bridge.prepare(turnId,"ws_default",List.of());
        assertEquals("SCRIPT_NOT_ACTIVATED",assertThrows(SkillException.class,()->fixture.adapter.prepare(turnId,fixture.skillId,fixture.resourceId,"root_test",List.of())).code());

        fixture.bridge.activate(turnId,fixture.skillId,SkillModels.ActivationReason.IMPLICIT);
        SkillScriptAdapter.PreparedScript prepared=fixture.adapter.prepare(turnId,fixture.skillId,fixture.resourceId,"root_test",List.of());
        Files.writeString(fixture.script,"Write-Output 'REPLACED'\n",StandardCharsets.UTF_8);
        String fingerprint="stale-invocation";
        ExecutionPermit permit=new DefaultPermissionEngine(false).allowOnce(new ResolvedApproval("apr_stale",ApprovalDecision.ALLOW_ONCE,request(turnId,fingerprint)));
        assertEquals("SCRIPT_STALE",assertThrows(SkillException.class,()->fixture.adapter.execute(prepared,permit,fingerprint,()->false)).code());
        assertFalse(Files.exists(fixture.workspace.resolve("week6-marker.txt")));
    }

    @Test
    void enforcesArgumentLimitsAndTimeout(@TempDir Path temp) throws Exception {
        Fixture fixture=fixture(temp,"Start-Sleep -Seconds 5\nSet-Content -LiteralPath 'late-marker.txt' -Value 'late'\n");
        String turnId="trn_timeout12";startTurn(fixture,turnId);fixture.bridge.prepare(turnId,"ws_default",List.of(fixture.skillId));
        assertEquals("TOOL_ARGUMENTS_INVALID",assertThrows(PermissionException.class,()->fixture.adapter.prepare(turnId,fixture.skillId,fixture.resourceId,"root_test",java.util.Collections.nCopies(33,"x"))).code());

        SkillScriptAdapter timed=new SkillScriptAdapter(new AgentScopeSkillAdapter(fixture.bridge),fixture.guard,Duration.ofMillis(150));
        SkillScriptAdapter.PreparedScript prepared=timed.prepare(turnId,fixture.skillId,fixture.resourceId,"root_test",List.of());
        String fingerprint="timeout-invocation";
        ExecutionPermit permit=new DefaultPermissionEngine(false).allowOnce(new ResolvedApproval("apr_timeout",ApprovalDecision.ALLOW_ONCE,request(turnId,fingerprint)));
        assertEquals("SCRIPT_TIMEOUT",assertThrows(PermissionException.class,()->timed.execute(prepared,permit,fingerprint,()->false)).code());
        assertEquals(0,timed.activeProcessCount());
        Thread.sleep(250);
        assertFalse(Files.exists(fixture.workspace.resolve("late-marker.txt")));
    }

    @Test
    void boundsOutputAndHonorsCancellation(@TempDir Path temp) throws Exception {
        Fixture outputFixture=fixture(temp.resolve("output"),"[Console]::Out.Write('x' * 600000)\n");
        String outputTurn="trn_output123";startTurn(outputFixture,outputTurn);outputFixture.bridge.prepare(outputTurn,"ws_default",List.of(outputFixture.skillId));
        SkillScriptAdapter.PreparedScript outputPlan=outputFixture.adapter.prepare(outputTurn,outputFixture.skillId,outputFixture.resourceId,"root_test",List.of());
        ExecutionPermit outputPermit=new DefaultPermissionEngine(false).allowOnce(new ResolvedApproval("apr_output",ApprovalDecision.ALLOW_ONCE,request(outputTurn,"output-invocation")));
        assertEquals("SCRIPT_OUTPUT_TOO_LARGE",assertThrows(PermissionException.class,()->outputFixture.adapter.execute(outputPlan,outputPermit,"output-invocation",()->false)).code());
        assertEquals(0,outputFixture.adapter.activeProcessCount());

        Fixture cancelFixture=fixture(temp.resolve("cancel"),"Start-Sleep -Seconds 5\nSet-Content -LiteralPath 'cancelled-marker.txt' -Value 'late'\n");
        String cancelTurn="trn_cancel123";startTurn(cancelFixture,cancelTurn);cancelFixture.bridge.prepare(cancelTurn,"ws_default",List.of(cancelFixture.skillId));
        SkillScriptAdapter.PreparedScript cancelPlan=cancelFixture.adapter.prepare(cancelTurn,cancelFixture.skillId,cancelFixture.resourceId,"root_test",List.of());
        ExecutionPermit cancelPermit=new DefaultPermissionEngine(false).allowOnce(new ResolvedApproval("apr_cancel",ApprovalDecision.ALLOW_ONCE,request(cancelTurn,"cancel-invocation")));
        AtomicBoolean cancelled=new AtomicBoolean();Thread canceller=Thread.ofVirtual().start(()->{try{Thread.sleep(150);cancelled.set(true);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertEquals("TOOL_CANCELLED",assertThrows(PermissionException.class,()->cancelFixture.adapter.execute(cancelPlan,cancelPermit,"cancel-invocation",cancelled::get)).code());
        assertEquals(0,cancelFixture.adapter.activeProcessCount());
        canceller.join();Thread.sleep(250);assertFalse(Files.exists(cancelFixture.workspace.resolve("cancelled-marker.txt")));
    }

    @Test
    void executesOnlyHashPinnedWorkspaceJavaScript(@TempDir Path temp) throws Exception {
        String node=System.getProperty("agentdesktop.node-executable");
        org.junit.jupiter.api.Assumptions.assumeTrue(node!=null&&Files.isRegularFile(Path.of(node)));
        Fixture fixture=fixture(temp,"Write-Output 'fixture'\n");String turnId="trn_node1234";startTurn(fixture,turnId);
        String body="require('node:fs').writeFileSync('node-marker.txt', process.argv[2], 'utf8'); console.log('NODE_OK');\n";
        Path script=fixture.workspace.resolve("report.js");Files.writeString(script,body,StandardCharsets.UTF_8);String hash=PermissionFingerprints.sha256(body);
        SkillScriptAdapter.PreparedScript prepared=fixture.adapter.prepareWorkspace(turnId,"root_test","report.js",hash,List.of("generated"));
        ExecutionPermit permit=new DefaultPermissionEngine(false).allowOnce(new ResolvedApproval("apr_node",ApprovalDecision.ALLOW_ONCE,request(turnId,"node-invocation")));

        SkillScriptAdapter.ScriptResult result=fixture.adapter.execute(prepared,permit,"node-invocation",()->false);

        assertTrue(result.succeeded());assertEquals("NODE_OK",result.summary());assertEquals("generated",Files.readString(fixture.workspace.resolve("node-marker.txt")));
        Files.writeString(script,body+"// changed",StandardCharsets.UTF_8);
        ExecutionPermit stalePermit=new DefaultPermissionEngine(false).allowOnce(new ResolvedApproval("apr_node_stale",ApprovalDecision.ALLOW_ONCE,request(turnId,"node-stale")));
        assertEquals("SCRIPT_STALE",assertThrows(PermissionException.class,()->fixture.adapter.execute(prepared,stalePermit,"node-stale",()->false)).code());
    }

    @Test
    void rejectsGeneratedRuntimeDependencyInstallers(@TempDir Path temp) throws Exception {
        Fixture fixture=fixture(temp,"Write-Output 'fixture'\n");String turnId="trn_install12";startTurn(fixture,turnId);
        String python="import subprocess, sys\nsubprocess.check_call([sys.executable, '-m', 'pip', 'install', 'python-pptx'])\n";
        Path script=fixture.workspace.resolve("install_pptx.py");Files.writeString(script,python,StandardCharsets.UTF_8);
        PermissionException denied=assertThrows(PermissionException.class,()->fixture.adapter.prepareWorkspace(
                turnId,"root_test","install_pptx.py",PermissionFingerprints.sha256(python),List.of()));
        assertEquals("RUNTIME_DEPENDENCY_INSTALL_DENIED",denied.code());
    }

    private static Fixture fixture(Path temp,String scriptBody) throws Exception {
        Path appHome=Files.createDirectories(temp.resolve("app"));Path workspace=Files.createDirectories(appHome.resolve("workspace"));Path builtin=Files.createDirectories(temp.resolve("builtin"));
        Path skill=Files.createDirectories(builtin.resolve("safe-script"));Path scripts=Files.createDirectories(skill.resolve("scripts"));Path script=scripts.resolve("safe.ps1");
        Files.writeString(skill.resolve("SKILL.md"),"---\nname: safe-script\ndescription: Execute the Week 6 safe fixture.\n---\nUse scripts/safe.ps1 only when requested.\n",StandardCharsets.UTF_8);
        Files.writeString(script,scriptBody,StandardCharsets.UTF_8);
        LocalDatabase database=new LocalDatabase(appHome);database.migrate();ObjectMapper mapper=new ObjectMapper();ConversationRepository conversations=new ConversationRepository(database,mapper);conversations.initializeProfile(workspace,"Test User");
        LocalSkillRegistry registry=new LocalSkillRegistry(database,mapper,builtin,appHome,workspace,"ws_default");registry.refreshAll();
        SkillModels.SkillSummary summary=registry.list(new SkillModels.SkillQuery(SkillModels.SkillScope.BUILTIN,SkillModels.SkillStatus.VALID)).items().getFirst();
        SkillModels.SkillResourceDescriptor resource=registry.listResources(summary.id()).stream().filter(item->item.relativePath().equals("scripts/safe.ps1")).findFirst().orElseThrow();
        PermissionRepository permissionRepository=new PermissionRepository(database,mapper);var attrs=Files.readAttributes(workspace,java.nio.file.attribute.BasicFileAttributes.class);
        permissionRepository.addRoot(new PermissionStore.FileRootDraft("root_test","ws_default","workspace",workspace.toRealPath().toString(),"path-hash",String.valueOf(attrs.fileKey()),"READ_WRITE",true,false,Instant.now()));
        FileRootGuard guard=new FileRootGuard(permissionRepository);DefaultSkillRuntimeBridge bridge=new DefaultSkillRuntimeBridge(registry);
        return new Fixture(workspace,script,summary.id(),resource.id(),conversations,bridge,guard,new SkillScriptAdapter(new AgentScopeSkillAdapter(bridge),guard,TEST_SCRIPT_TIMEOUT));
    }

    private static void startTurn(Fixture fixture,String turnId){String suffix=turnId.substring(4);fixture.conversations.startTurn(new ConversationStore.StartTurnCommand("thr_"+suffix,turnId,"run_"+suffix,"mock","itm_"+suffix,"hello",Instant.now()));}
    private static PermissionRequest request(String turnId,String fingerprint){PermissionSubject subject=new PermissionSubject("SKILL_SCRIPT","skill.run_script","safe-script","skl_test","source");ResourceScope scope=new ResourceScope("root_test",null,null,"skl_test","res_test","hash","script");return new PermissionRequest("call_script","thr_script",turnId,CapabilityClass.EXECUTE,subject,scope,"args",fingerprint,"grant",new SafeInvocationSummary("critical","title","summary",Map.of(),Map.of(),"warning"),null,Instant.now());}
    private record Fixture(Path workspace,Path script,String skillId,String resourceId,ConversationRepository conversations,DefaultSkillRuntimeBridge bridge,FileRootGuard guard,SkillScriptAdapter adapter){}
}
