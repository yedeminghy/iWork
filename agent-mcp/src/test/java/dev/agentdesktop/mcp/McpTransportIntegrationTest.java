package dev.agentdesktop.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.mcp.McpModels.*;
import dev.agentdesktop.persistence.LocalDatabase;
import dev.agentdesktop.persistence.McpCatalogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpTransportIntegrationTest {
    private static final Duration TEST_STARTUP_TIMEOUT=Duration.ofSeconds(180);
    private static final Duration TEST_OPERATION_TIMEOUT=Duration.ofSeconds(10);
    private final List<SdkMcpClientManager> managers=new ArrayList<>();

    @AfterEach void closeManagers() throws InterruptedException {
        managers.forEach(SdkMcpClientManager::close);
        managers.clear();
        if(System.getProperty("os.name").toLowerCase().contains("win"))Thread.sleep(2_000);
    }

    @Test void stdioListsAndCallsEcho(@TempDir Path home) throws Exception {
        Harness harness=harness(home);Path node=findNode();Path fixture=fixtureScript();
        var saved=harness.manager.save(new StdioServerDraft(null,"stdio-fixture",node.toString(),List.of(fixture.toString(),"stdio"),harness.workspace,List.of(),true,ReconnectPolicy.defaults()));
        harness.manager.connect(saved.id(),EphemeralCredential.empty());
        assertThat(harness.manager.listTools(saved.id())).extracting(ToolDescriptor::name).contains("echo","sum");
        ToolCallResult result=harness.manager.call(new ToolInvocation("call_stdio","thr_test","trn_test","mcp:"+saved.id()+":echo",harness.mapper.readTree("{\"text\":\"hello\"}"),Instant.now()),()->false);
        assertThat(result.outcome()).isEqualTo(ToolOutcome.SUCCEEDED);assertThat(result.userSummary()).contains("hello");harness.manager.close();
    }

    @Test void streamableHttpUsesBearerAndCallsSum(@TempDir Path home) throws Exception {
        Path node=findNode();ProcessBuilder builder=new ProcessBuilder(node.toString(),fixtureScript().toString(),"http").redirectErrorStream(true);builder.environment().put("AGENT_W3_FIXTURE_TOKEN","integration-secret");Process server=builder.start();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(server.getInputStream()))){String ready=reader.readLine();int port=new ObjectMapper().readTree(ready).path("port").asInt();Harness harness=harness(home);
            var saved=harness.manager.save(new StreamableHttpServerDraft(null,"http-fixture",URI.create("http://127.0.0.1:"+port+"/mcp"),null,true,ReconnectPolicy.defaults()));
            harness.manager.connect(saved.id(),new EphemeralCredential("integration-secret",Map.of()));ToolCallResult result=harness.manager.call(new ToolInvocation("call_http","thr_test","trn_test","mcp:"+saved.id()+":sum",harness.mapper.readTree("{\"a\":2,\"b\":3}"),Instant.now()),()->false);
            assertThat(result.outcome()).isEqualTo(ToolOutcome.SUCCEEDED);assertThat(result.userSummary()).contains("5");harness.manager.close();
        }finally{server.destroyForcibly();}
    }

    @Test void authFailureIsStableAndManagerDoesNotApplyPermissionPolicy(@TempDir Path home)throws Exception{
        Path node=findNode();Path requestMarker=home.resolve("auth-requests.txt");ProcessBuilder builder=new ProcessBuilder(node.toString(),fixtureScript().toString(),"http").redirectErrorStream(true);builder.environment().put("AGENT_W3_FIXTURE_TOKEN","required-secret");builder.environment().put("AGENT_W3_HTTP_REQUEST_MARKER",requestMarker.toString());Process server=builder.start();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(server.getInputStream()))){int port=new ObjectMapper().readTree(reader.readLine()).path("port").asInt();Harness harness=harness(home);var saved=harness.manager.save(new StreamableHttpServerDraft(null,"auth-fixture",URI.create("http://127.0.0.1:"+port+"/mcp"),null,true,new ReconnectPolicy(3,java.time.Duration.ofMillis(10),java.time.Duration.ofMillis(20))));
            long started=System.nanoTime();assertThatThrownBy(()->harness.manager.connect(saved.id(),EphemeralCredential.empty())).isInstanceOf(McpException.class);assertThat(Files.readAllLines(requestMarker)).hasSize(1);assertThat(java.time.Duration.ofNanos(System.nanoTime()-started)).isLessThan(TEST_STARTUP_TIMEOUT);
            harness.manager.connect(saved.id(),new EphemeralCredential("required-secret",Map.of()));ToolCallResult result=harness.manager.call(new ToolInvocation("call_danger","thr_test","trn_test","mcp:"+saved.id()+":danger",harness.mapper.createObjectNode(),Instant.now()),()->false);assertThat(result.errorCode()).isNotEqualTo("TOOL_APPROVAL_REQUIRED");harness.manager.close();
        }finally{server.destroyForcibly();}
    }

    @Test void delayedCallPropagatesCancellation(@TempDir Path home)throws Exception{
        Harness harness=harness(home);Path node=findNode();var saved=harness.manager.save(new StdioServerDraft(null,"cancel-fixture",node.toString(),List.of(fixtureScript().toString(),"stdio"),harness.workspace,List.of(),true,ReconnectPolicy.defaults()));harness.manager.connect(saved.id(),EphemeralCredential.empty());java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        Thread.ofVirtual().start(()->{try{Thread.sleep(150);}catch(InterruptedException ignored){}cancelled.set(true);});long started=System.nanoTime();ToolCallResult result=harness.manager.call(new ToolInvocation("call_cancel","thr_test","trn_test","mcp:"+saved.id()+":echo",harness.mapper.readTree("{\"text\":\"__delay__\"}"),Instant.now()),cancelled::get);assertThat(result.errorCode()).isEqualTo("TOOL_CANCELLED");assertThat(java.time.Duration.ofNanos(System.nanoTime()-started)).isLessThan(TEST_OPERATION_TIMEOUT);harness.manager.close();
    }

    @Test void invalidArgumentsAreRejectedBeforeTransportCall(@TempDir Path home)throws Exception{
        Harness harness=harness(home);Path node=findNode();var saved=harness.manager.save(new StdioServerDraft(null,"schema-fixture",node.toString(),List.of(fixtureScript().toString(),"stdio"),harness.workspace,List.of(),true,ReconnectPolicy.defaults()));harness.manager.connect(saved.id(),EphemeralCredential.empty());
        ToolCallResult result=harness.manager.call(new ToolInvocation("call_schema","thr_test","trn_test","mcp:"+saved.id()+":sum",harness.mapper.readTree("{\"a\":\"wrong\",\"b\":3}"),Instant.now()),()->false);
        assertThat(result.errorCode()).isEqualTo("TOOL_ARGUMENT_INVALID");harness.manager.close();
    }

    @Test void http5xxRetriesAndRefreshesCatalog(@TempDir Path home)throws Exception{
        Process server=startHttpFixture(Map.of("AGENT_W3_HTTP_FAIL_FIRST","1"));
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(server.getInputStream()))){int port=new ObjectMapper().readTree(reader.readLine()).path("port").asInt();Harness harness=harness(home);var saved=harness.manager.save(new StreamableHttpServerDraft(null,"retry-fixture",URI.create("http://127.0.0.1:"+port+"/mcp"),null,true,new ReconnectPolicy(2,Duration.ofMillis(10),Duration.ofMillis(20))));
            McpServerSummary connected=harness.manager.connect(saved.id(),EphemeralCredential.empty());assertThat(connected.status()).isEqualTo("READY");assertThat(connected.catalogVersion()).isGreaterThan(0);assertThat(harness.manager.listTools(saved.id())).extracting(ToolDescriptor::name).contains("echo","sum");harness.manager.close();
        }finally{server.destroyForcibly();server.waitFor();}
    }

    @Test void droppedHttpConnectionRetriesAndRecovers(@TempDir Path home)throws Exception{
        Process server=startHttpFixture(Map.of("AGENT_W3_HTTP_DROP_FIRST","1"));
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(server.getInputStream()))){int port=new ObjectMapper().readTree(reader.readLine()).path("port").asInt();Harness harness=harness(home);var saved=harness.manager.save(new StreamableHttpServerDraft(null,"drop-fixture",URI.create("http://127.0.0.1:"+port+"/mcp"),null,true,new ReconnectPolicy(2,Duration.ofMillis(10),Duration.ofMillis(20))));
            assertThat(harness.manager.connect(saved.id(),EphemeralCredential.empty()).status()).isEqualTo("READY");harness.manager.close();
        }finally{server.destroyForcibly();server.waitFor();}
    }

    @Test void timeoutHasSingleStableTerminalAndConnectionCanClose(@TempDir Path home)throws Exception{
        Harness harness=harness(home,Duration.ofMillis(250));Path node=findNode();var saved=harness.manager.save(new StdioServerDraft(null,"timeout-fixture",node.toString(),List.of(fixtureScript().toString(),"stdio"),harness.workspace,List.of(),true,ReconnectPolicy.defaults()));harness.manager.connect(saved.id(),EphemeralCredential.empty());
        ToolCallResult result=harness.manager.call(new ToolInvocation("call_timeout","thr_test","trn_test","mcp:"+saved.id()+":echo",harness.mapper.readTree("{\"text\":\"__delay__\"}"),Instant.now()),()->false);
        assertThat(result.outcome()).isEqualTo(ToolOutcome.FAILED);assertThat(result.errorCode()).isEqualTo("MCP_TIMEOUT");assertThat(harness.manager.recentDiagnostic(saved.id()).status()).isEqualTo("FAILED");assertThat(harness.manager.connect(saved.id(),EphemeralCredential.empty()).status()).isEqualTo("READY");harness.manager.disconnect(saved.id());assertThat(harness.manager.recentDiagnostic(saved.id()).status()).isEqualTo("CONFIGURED");harness.manager.close();Thread.sleep(100);
    }

    @Test void stdioProcessExitIsMappedAndReconnectsWithoutLeaking(@TempDir Path home)throws Exception{
        Harness harness=harness(home,Duration.ofSeconds(2));Path node=findNode();Path marker=harness.workspace.resolve("exit-once.marker");Files.createFile(marker);var saved=harness.manager.save(new StdioServerDraft(null,"exit-fixture",node.toString(),List.of(fixtureScript().toString(),"stdio-exit-once",marker.toString()),harness.workspace,List.of(),true,new ReconnectPolicy(0,Duration.ZERO,Duration.ZERO)));harness.manager.connect(saved.id(),EphemeralCredential.empty());
        Thread.sleep(750);long started=System.nanoTime();ToolCallResult exited=harness.manager.call(new ToolInvocation("call_exit","thr_test","trn_test","mcp:"+saved.id()+":echo",harness.mapper.readTree("{\"text\":\"after-exit\"}"),Instant.now()),()->false);
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(TEST_OPERATION_TIMEOUT);
        assertThat(exited.errorCode()).isEqualTo("MCP_PROCESS_EXITED");assertThat(harness.manager.recentDiagnostic(saved.id()).status()).isEqualTo("FAILED");
        assertThat(harness.manager.connect(saved.id(),EphemeralCredential.empty()).status()).isEqualTo("READY");harness.manager.disconnect(saved.id());assertThat(harness.manager.recentDiagnostic(saved.id()).status()).isEqualTo("CONFIGURED");harness.manager.close();Thread.sleep(100);
    }

    @Test void deleteDisconnectsHidesAndDisablesTools(@TempDir Path home)throws Exception{
        Harness harness=harness(home);Path node=findNode();var saved=harness.manager.save(new StdioServerDraft(null,"delete-fixture",node.toString(),List.of(fixtureScript().toString(),"stdio"),harness.workspace,List.of(),true,ReconnectPolicy.defaults()));harness.manager.connect(saved.id(),EphemeralCredential.empty());String toolId="mcp:"+saved.id()+":echo";
        harness.manager.delete(saved.id());
        assertThat(harness.manager.list(50,null).items()).isEmpty();
        assertThatThrownBy(()->harness.manager.listTools(saved.id())).isInstanceOf(McpCatalogRepository.DomainException.class).hasMessage("MCP_SERVER_NOT_FOUND");
        ToolCallResult unavailable=harness.manager.call(new ToolInvocation("call_deleted","thr_test","trn_test",toolId,harness.mapper.readTree("{\"text\":\"hello\"}"),Instant.now()),()->false);
        assertThat(unavailable.outcome()).isEqualTo(ToolOutcome.FAILED);assertThat(unavailable.errorCode()).isEqualTo("TOOL_DISABLED");harness.manager.close();
    }

    private Harness harness(Path home)throws Exception{return harness(home,Duration.ofSeconds(30));}
    private Harness harness(Path home,Duration timeout)throws Exception{Path workspace=home.resolve("workspace");Files.createDirectories(workspace);LocalDatabase db=new LocalDatabase(home);db.migrate();ObjectMapper mapper=new ObjectMapper();SdkMcpClientManager manager=new SdkMcpClientManager(new McpCatalogRepository(db,mapper),mapper,workspace,true,timeout,TEST_STARTUP_TIMEOUT);managers.add(manager);return new Harness(manager,mapper,workspace);}
    private static Process startHttpFixture(Map<String,String> environment)throws Exception{ProcessBuilder builder=new ProcessBuilder(findNode().toString(),fixtureScript().toString(),"http").redirectErrorStream(true);builder.environment().putAll(environment);return builder.start();}
    private static Path fixtureScript(){Path module=Path.of(System.getProperty("user.dir")).toAbsolutePath();Path root=module.getFileName().toString().equals("agent-mcp")?module.getParent():module;return root.resolve("scripts/mcp-week3-fixture.mjs").normalize();}
    private static Path findNode()throws Exception{String binary=System.getProperty("os.name").toLowerCase().contains("win")?"node.exe":"node";for(String part:System.getenv("PATH").split(java.io.File.pathSeparator)){Path candidate=Path.of(part).resolve(binary);if(Files.isRegularFile(candidate))return candidate.toRealPath();}Process process=new ProcessBuilder(System.getProperty("os.name").toLowerCase().contains("win")?"where.exe":"which",binary).start();String line=new BufferedReader(new InputStreamReader(process.getInputStream())).readLine();if(line==null)throw new IllegalStateException("Node.js is required for MCP fixture tests");return Path.of(line).toRealPath();}
    private record Harness(SdkMcpClientManager manager,ObjectMapper mapper,Path workspace){}
}
