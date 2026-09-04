package dev.agentdesktop.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class PermissionRepositoryTest {
    @Test void replacesDefaultRootWhenConfiguredWorkspaceChanges(@TempDir Path home)throws Exception{
        LocalDatabase database=new LocalDatabase(home);database.migrate();ObjectMapper mapper=new ObjectMapper();ConversationRepository conversations=new ConversationRepository(database,mapper);conversations.initializeProfile(home.resolve("workspace"),"test");
        PermissionRepository permissions=new PermissionRepository(database,mapper);Path first=Files.createDirectories(home.resolve("first"));Path second=Files.createDirectories(home.resolve("second"));Instant now=Instant.now();
        var initial=permissions.ensureDefaultRoot(new PermissionStore.FileRootDraft("root_default","ws_default","Default Workspace",first.toRealPath().toString(),"hash-first","identity-first","READ_WRITE",true,true,now));
        var replaced=permissions.ensureDefaultRoot(new PermissionStore.FileRootDraft("root_default","ws_default","Default Workspace",second.toRealPath().toString(),"hash-second","identity-second","READ_WRITE",true,true,now.plusSeconds(1)));
        assertThat(replaced.id()).isEqualTo(initial.id());assertThat(replaced.pathHash()).isEqualTo("hash-second");assertThat(replaced.version()).isEqualTo(initial.version()+1);assertThat(replaced.enabled()).isTrue();
        try(var connection=database.open();var statement=connection.prepareStatement("SELECT COUNT(*) FROM permission_audit WHERE subject_id='root_default' AND action='ROOT_REPLACE'" );var result=statement.executeQuery()){assertThat(result.getInt(1)).isEqualTo(1);}
    }

    @Test void requestAndResolveAreAtomicAndProjected(@TempDir Path home){
        LocalDatabase database=new LocalDatabase(home);database.migrate();ObjectMapper mapper=new ObjectMapper();ConversationRepository conversations=new ConversationRepository(database,mapper);
        Path workspace=home.resolve("workspace");conversations.initializeProfile(workspace,"test");Instant now=Instant.now();conversations.startTurn(new ConversationStore.StartTurnCommand("thr_permission","trn_permission","run_permission","mock","itm_user","hello",now));
        PermissionRepository permissions=new PermissionRepository(database,mapper);String fingerprint="sha256:"+"a".repeat(64);
        Map<String,Object> safe=Map.of("approvalId","apr_permission","toolCallId","call_permission","requestFingerprint",fingerprint,"title","Allow?");
        var requested=permissions.requestApproval(new PermissionStore.ApprovalDraft("apr_permission","thr_permission","trn_permission","call_permission","mcp:s:t","UNKNOWN","critical",fingerprint,"subject","args","scope",safe,now.plus(5,ChronoUnit.MINUTES),now));
        assertThat(requested.sequence()).isPositive();assertThat(conversations.loadThreadSnapshot("thr_permission").turns().getFirst().status()).isEqualTo("WAITING_APPROVAL");
        var resolved=permissions.resolveApproval("apr_permission","DENY",fingerprint,now.plusSeconds(1));assertThat(resolved.approval().decision()).isEqualTo("DENY");
        ThreadSnapshot snapshot=conversations.loadThreadSnapshot("thr_permission");assertThat(snapshot.schemaVersion()).isEqualTo(2);assertThat(snapshot.timeline()).anySatisfy(item->{assertThat(item.kind()).isEqualTo("APPROVAL");assertThat(item.value()).containsEntry("decision","DENY");});
        assertThat(permissions.resolveApproval("apr_permission","DENY",fingerprint,now.plusSeconds(2)).replayed()).isTrue();
    }

    @Test void expiredResolutionFailsTheToolAndResumesTheTurn(@TempDir Path home){
        LocalDatabase database=new LocalDatabase(home);database.migrate();ObjectMapper mapper=new ObjectMapper();ConversationRepository conversations=new ConversationRepository(database,mapper);conversations.initializeProfile(home.resolve("workspace"),"test");Instant now=Instant.now();conversations.startTurn(new ConversationStore.StartTurnCommand("thr_expired","trn_expired","run_expired","mock","itm_user2","hello",now));
        PermissionRepository permissions=new PermissionRepository(database,mapper);String fp="sha256:"+"b".repeat(64);Map<String,Object> safe=Map.of("approvalId","apr_expired","toolCallId","call_expired","requestFingerprint",fp);
        permissions.requestApproval(new PermissionStore.ApprovalDraft("apr_expired","thr_expired","trn_expired","call_expired","tool","WRITE","high",fp,"subject","args","scope",safe,now.minusSeconds(1),now.minusSeconds(2)));
        assertThatThrownBy(()->permissions.resolveApproval("apr_expired","ALLOW_ONCE",fp,now)).isInstanceOf(PermissionRepository.DomainException.class).hasMessage("APPROVAL_EXPIRED");
        assertThat(permissions.pendingForTurn("trn_expired")).isEmpty();assertThat(conversations.loadThreadSnapshot("thr_expired").turns().getFirst().status()).isEqualTo("RUNNING");
        assertThat(conversations.loadThreadSnapshot("thr_expired").timeline()).anySatisfy(item->{assertThat(item.kind()).isEqualTo("APPROVAL");assertThat(item.value()).containsEntry("decision","EXPIRED");});
    }

    @Test void restartInvalidationIsProjectedAndAuditedAtomically(@TempDir Path home)throws Exception{
        LocalDatabase database=new LocalDatabase(home);database.migrate();ObjectMapper mapper=new ObjectMapper();ConversationRepository conversations=new ConversationRepository(database,mapper);conversations.initializeProfile(home.resolve("workspace"),"test");Instant now=Instant.now();conversations.startTurn(new ConversationStore.StartTurnCommand("thr_restart","trn_restart","run_restart","mock","itm_restart","hello",now));
        PermissionRepository permissions=new PermissionRepository(database,mapper);String fp="sha256:"+"c".repeat(64);Map<String,Object> safe=Map.of("approvalId","apr_restart","toolCallId","call_restart","requestFingerprint",fp);
        permissions.requestApproval(new PermissionStore.ApprovalDraft("apr_restart","thr_restart","trn_restart","call_restart","tool","WRITE","high",fp,"subject","args","scope",safe,now.plusSeconds(300),now));

        conversations.reconcileUnfinishedTurns(now.plusSeconds(1));ThreadSnapshot snapshot=conversations.loadThreadSnapshot("thr_restart");
        assertThat(snapshot.turns()).singleElement().satisfies(turn->{assertThat(turn.status()).isEqualTo("CANCELLED");assertThat(turn.errorCode()).isEqualTo("APPROVAL_INVALIDATED");});
        assertThat(snapshot.timeline()).anySatisfy(item->{assertThat(item.kind()).isEqualTo("APPROVAL");assertThat(item.value()).containsEntry("decision","INVALIDATED");});
        try(var connection=database.open();var statement=connection.prepareStatement("SELECT COUNT(*) FROM permission_audit WHERE approval_id=? AND action='INVALIDATE' AND error_code='APPROVAL_INVALIDATED'")){statement.setString(1,"apr_restart");try(var result=statement.executeQuery()){assertThat(result.getInt(1)).isEqualTo(1);}}
    }

    @Test void recordsAutomaticAllowsAndHardDenialsWithoutSensitiveArguments(@TempDir Path home)throws Exception{
        LocalDatabase database=new LocalDatabase(home);database.migrate();ObjectMapper mapper=new ObjectMapper();
        ConversationRepository conversations=new ConversationRepository(database,mapper);conversations.initializeProfile(home.resolve("workspace"),"test");
        Instant now=Instant.now();conversations.startTurn(new ConversationStore.StartTurnCommand("thr_policy","trn_policy","run_policy","mock","itm_policy","hello",now));
        PermissionRepository permissions=new PermissionRepository(database,mapper);
        permissions.recordPolicyEvaluation(new PermissionStore.PolicyAuditDraft("thr_policy","trn_policy","call_allow","READ","subject","subject-fp","scope-hash","AUTO_ALLOW_POLICY",null,now));
        permissions.recordPolicyEvaluation(new PermissionStore.PolicyAuditDraft("thr_policy","trn_policy","call_deny","EXECUTE","subject","subject-fp","scope-hash","HARD_DENY","TOOL_DISABLED",now.plusSeconds(1)));
        try(var connection=database.open();var statement=connection.prepareStatement("SELECT call_id,decision,error_code,subject_fingerprint,resource_scope_hash FROM permission_audit WHERE action='POLICY_EVALUATION' ORDER BY call_id");var result=statement.executeQuery()){
            assertThat(result.next()).isTrue();assertThat(result.getString("call_id")).isEqualTo("call_allow");assertThat(result.getString("decision")).isEqualTo("AUTO_ALLOW_POLICY");
            assertThat(result.getString("subject_fingerprint")).isEqualTo("subject-fp");assertThat(result.getString("resource_scope_hash")).isEqualTo("scope-hash");
            assertThat(result.next()).isTrue();assertThat(result.getString("call_id")).isEqualTo("call_deny");assertThat(result.getString("decision")).isEqualTo("HARD_DENY");assertThat(result.getString("error_code")).isEqualTo("TOOL_DISABLED");
            assertThat(result.next()).isFalse();
        }
    }
}
