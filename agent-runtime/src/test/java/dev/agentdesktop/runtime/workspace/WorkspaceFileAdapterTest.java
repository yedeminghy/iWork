package dev.agentdesktop.runtime.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.persistence.*;
import dev.agentdesktop.runtime.permission.DefaultPermissionEngine;
import dev.agentdesktop.runtime.permission.PermissionException;
import dev.agentdesktop.runtime.permission.PermissionModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceFileAdapterTest {
    @Test void rejectsTraversalAndReadOnlyWrite(@TempDir Path home)throws Exception{
        Fixture f=fixture(home,"READ_ONLY");
        assertEquals("FILE_PATH_INVALID",assertThrows(PermissionException.class,()->f.guard.resolve("root_test","../outside.txt",false)).code());
        assertEquals("FILE_ROOT_MODE_DENIED",assertThrows(PermissionException.class,()->f.guard.resolve("root_test","safe.txt",true)).code());
    }
    @Test void resolvesDefaultAliasesAndReportsUnknownRoot(@TempDir Path home)throws Exception{
        Fixture f=fixture(home,"READ_WRITE",FileRootGuard.DEFAULT_ROOT_ID);
        assertEquals(FileRootGuard.DEFAULT_ROOT_ID,f.guard.resolve("ws_default","safe.txt",true).root().id());
        assertEquals(FileRootGuard.DEFAULT_ROOT_ID,f.guard.resolve("default","safe.txt",true).root().id());
        assertEquals(FileRootGuard.DEFAULT_ROOT_ID,f.guard.resolve("workspace","safe.txt",true).root().id());
        assertEquals("FILE_ROOT_NOT_FOUND",assertThrows(PermissionException.class,()->f.guard.resolve("missing","safe.txt",true)).code());
    }
    @Test void readsUtf8AndWritesAtomicallyWithPermit(@TempDir Path home)throws Exception{
        Fixture f=fixture(home,"READ_WRITE");Files.writeString(f.root.resolve("input.txt"),"hello");PermissionRequest read=request("call_read","read-inv");ExecutionPermit readPermit=new DefaultPermissionEngine(false).allowOnce(new ResolvedApproval("apr_read",ApprovalDecision.ALLOW_ONCE,read));
        var readResult=f.adapter.read(f.guard.resolve("root_test","input.txt",false),readPermit,"read-inv");assertEquals("hello",readResult.modelContent());
        PermissionRequest write=request("call_write","write-inv");ExecutionPermit writePermit=new DefaultPermissionEngine(false).allowOnce(new ResolvedApproval("apr_write",ApprovalDecision.ALLOW_ONCE,write));
        var writeResult=f.adapter.write(f.guard.resolve("root_test","output.txt",true),"world",false,writePermit,"write-inv");assertTrue(writeResult.written());assertEquals("world",Files.readString(f.root.resolve("output.txt")));
        assertEquals("FILE_ALREADY_EXISTS",assertThrows(PermissionException.class,()->{ExecutionPermit p=new DefaultPermissionEngine(false).allowOnce(new ResolvedApproval("apr_write2",ApprovalDecision.ALLOW_ONCE,write));f.adapter.write(f.guard.resolve("root_test","output.txt",true),"changed",false,p,"write-inv");}).code());
        assertEquals("world",Files.readString(f.root.resolve("output.txt")));
    }
    @Test void rejectsBinaryInvalidUtf8AndOversizedContent(@TempDir Path home)throws Exception{
        Fixture f=fixture(home,"READ_WRITE");
        Files.write(f.root.resolve("binary.txt"),new byte[]{'a',0,'b'});
        Files.write(f.root.resolve("invalid.txt"),new byte[]{(byte)0xC3,(byte)0x28});
        Files.write(f.root.resolve("large.txt"),new byte[WorkspaceFileAdapter.MAX_BYTES+1]);
        assertReadCode(f,"binary.txt","FILE_BINARY_UNSUPPORTED","binary-read");
        assertReadCode(f,"invalid.txt","FILE_NOT_UTF8","invalid-read");
        assertReadCode(f,"large.txt","FILE_TOO_LARGE","large-read");
        ExecutionPermit permit=permit(request("call_large_write","large-write"),"apr_large_write");
        assertEquals("FILE_TOO_LARGE",assertThrows(PermissionException.class,()->f.adapter.write(
                f.guard.resolve("root_test","too-large.txt",true),"x".repeat(WorkspaceFileAdapter.MAX_BYTES+1),false,permit,"large-write")).code());
    }

    @Test void rejectsDirectoryLinksAndParentIdentityChanges(@TempDir Path home)throws Exception{
        Fixture f=fixture(home,"READ_WRITE");Path outside=Files.createDirectories(home.resolve("outside"));Files.writeString(outside.resolve("secret.txt"),"secret");
        createDirectoryLink(f.root.resolve("linked"),outside);
        assertEquals("FILE_PATH_LINK_UNSUPPORTED",assertThrows(PermissionException.class,()->f.guard.resolve("root_test","linked/secret.txt",false)).code());

        Path parent=Files.createDirectories(f.root.resolve("stable"));Files.writeString(parent.resolve("item.txt"),"original");
        FileRootGuard.ResolvedFile resolved=f.guard.resolve("root_test","stable/item.txt",false);
        Files.move(parent,f.root.resolve("replaced-stable"));Path replacement=Files.createDirectories(f.root.resolve("stable"));Files.writeString(replacement.resolve("item.txt"),"replacement");
        ExecutionPermit permit=permit(request("call_race","race-read"),"apr_race");
        assertEquals("FILE_PATH_CHANGED",assertThrows(PermissionException.class,()->f.adapter.read(resolved,permit,"race-read")).code());
    }

    private static void assertReadCode(Fixture f,String path,String code,String fingerprint){
        ExecutionPermit permit=permit(request("call_"+fingerprint,fingerprint),"apr_"+fingerprint);
        assertEquals(code,assertThrows(PermissionException.class,()->f.adapter.read(f.guard.resolve("root_test",path,false),permit,fingerprint)).code());
    }
    private static ExecutionPermit permit(PermissionRequest request,String id){return new DefaultPermissionEngine(false).allowOnce(new ResolvedApproval(id,ApprovalDecision.ALLOW_ONCE,request));}
    private static void createDirectoryLink(Path link,Path target)throws Exception{
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")){
            Process process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(10,TimeUnit.SECONDS),"junction creation timed out");
            assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8));
        }else Files.createSymbolicLink(link,target);
    }
    private static PermissionRequest request(String call,String fingerprint){PermissionSubject subject=new PermissionSubject("WORKSPACE_FILE","workspace.read_text","file","root_test","source");ResourceScope scope=new ResourceScope("root_test","input.txt",null,null,null,null,"file");return new PermissionRequest(call,"thr_test","trn_test",CapabilityClass.READ,subject,scope,"args",fingerprint,"grant",new SafeInvocationSummary("medium","title","summary",Map.of(),Map.of(),""),null,Instant.now());}
    private static Fixture fixture(Path home,String mode)throws Exception{return fixture(home,mode,"root_test");}
    private static Fixture fixture(Path home,String mode,String rootId)throws Exception{Path root=Files.createDirectories(home.resolve("root"));LocalDatabase database=new LocalDatabase(home.resolve("app"));database.migrate();ConversationRepository conversations=new ConversationRepository(database,new ObjectMapper());conversations.initializeProfile(home.resolve("workspace"),"test");PermissionRepository repo=new PermissionRepository(database,new ObjectMapper());var attrs=Files.readAttributes(root,java.nio.file.attribute.BasicFileAttributes.class);repo.addRoot(new PermissionStore.FileRootDraft(rootId,"ws_default","root",root.toRealPath().toString(),"hash",String.valueOf(attrs.fileKey()),mode,true,false,Instant.now()));FileRootGuard guard=new FileRootGuard(repo);return new Fixture(root,guard,new WorkspaceFileAdapter(guard));}
    private record Fixture(Path root,FileRootGuard guard,WorkspaceFileAdapter adapter){}
}
