package dev.agentdesktop.runtime.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.runtime.permission.PermissionModels.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultPermissionEngineTest {
    private final ObjectMapper mapper=new ObjectMapper();

    @Test void canonicalArgumentsAndInvocationAreDeterministic()throws Exception{
        String first=PermissionFingerprints.argumentsHash(mapper,mapper.readTree("{\"b\":2,\"a\":1}"));
        String second=PermissionFingerprints.argumentsHash(mapper,mapper.readTree("{\"a\":1,\"b\":2}"));
        assertEquals(first,second);assertTrue(first.matches("sha256:[0-9a-f]{64}"));
    }

    @Test void unknownAsksAndHardDenyWins(){
        DefaultPermissionEngine engine=new DefaultPermissionEngine(false);PermissionRequest request=request(CapabilityClass.UNKNOWN,null,"grant-a");
        assertInstanceOf(ApprovalRequired.class,engine.evaluate(request));
        assertEquals(new Denied("TOOL_DISABLED"),engine.evaluate(request(CapabilityClass.UNKNOWN,"TOOL_DISABLED","grant-a")));
    }

    @Test void oncePermitIsSingleUseAndThreadGrantIsScoped(){
        DefaultPermissionEngine engine=new DefaultPermissionEngine(false);PermissionRequest request=request(CapabilityClass.WRITE,null,"grant-a");
        ExecutionPermit once=engine.allowOnce(new ResolvedApproval("apr_test",ApprovalDecision.ALLOW_ONCE,request));once.consume(request.invocationFingerprint());
        assertEquals("PERMISSION_PERMIT_CONSUMED",assertThrows(PermissionException.class,()->once.consume(request.invocationFingerprint())).code());
        engine.allowThread(new ResolvedApproval("apr_test2",ApprovalDecision.ALLOW_THREAD,request));
        assertEquals(new Allowed(GrantSource.ALLOW_THREAD),engine.evaluate(request(CapabilityClass.WRITE,null,"grant-a")));
        assertInstanceOf(ApprovalRequired.class,engine.evaluate(request(CapabilityClass.WRITE,null,"grant-b")));
        engine.clearThread("thr_test");assertInstanceOf(ApprovalRequired.class,engine.evaluate(request));
    }

    @Test void demoSafeOnlyAutoAllowsInFixtureMode(){
        assertEquals(new Allowed(GrantSource.TEST_FIXTURE),new DefaultPermissionEngine(true).evaluate(request(CapabilityClass.DEMO_SAFE,null,"demo")));
        assertInstanceOf(ApprovalRequired.class,new DefaultPermissionEngine(false).evaluate(request(CapabilityClass.DEMO_SAFE,null,"demo")));
    }

    private static PermissionRequest request(CapabilityClass capability,String deny,String grant){
        PermissionSubject subject=new PermissionSubject("MCP_TOOL","mcp:s:t","tool","s","source");ResourceScope resource=new ResourceScope(null,null,"",null,null,null,"mcp:s:t");
        return new PermissionRequest("call_test","thr_test","trn_test",capability,subject,resource,"sha256:args","sha256:invocation",grant,new SafeInvocationSummary("critical","title","summary",Map.of(),Map.of(),""),deny,Instant.now());
    }
}
