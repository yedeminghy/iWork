package dev.agentdesktop.host;

import dev.agentdesktop.mcp.McpClientManager;
import dev.agentdesktop.persistence.LocalDatabase;
import dev.agentdesktop.runtime.skill.SkillScriptAdapter;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Builds diagnostics from an explicit field allowlist; it never reads timeline payloads or raw logs. */
@Service
public final class DiagnosticService {
    private static final List<String> EXCLUDED=List.of("conversation and steering text","system and Skill instructions","tool arguments and results","MCP stderr and raw logs","file names and paths","environment variables and credentials","HTTP headers, cookies and reasoning");
    private final LocalDatabase database;
    private final ProviderService providers;
    private final McpClientManager mcp;
    private final TurnCoordinator turns;
    private final ApprovalCoordinator approvals;
    private final ProviderSecretSessionStore secretSessions;
    private final SkillScriptAdapter scripts;

    public DiagnosticService(LocalDatabase database,ProviderService providers,McpClientManager mcp,TurnCoordinator turns,
                             ApprovalCoordinator approvals,ProviderSecretSessionStore secretSessions,SkillScriptAdapter scripts){
        this.database=database;this.providers=providers;this.mcp=mcp;this.turns=turns;this.approvals=approvals;this.secretSessions=secretSessions;this.scripts=scripts;
    }

    public DiagnosticPreview preview(){
        LocalDatabase.DiagnosticStats db=database.diagnosticStats();
        Runtime runtime=Runtime.getRuntime();
        ThreadMXBean threads=ManagementFactory.getThreadMXBean();
        McpClientManager.OperationalStats mcpOperations=mcp.operationalStats();
        List<Map<String,Object>> providerStates=providers.list().stream().map(profile->safeMap("profileId",profile.id(),"status",profile.status(),"lastErrorCode",profile.lastErrorCode()==null?"NONE":profile.lastErrorCode(),"observedCapabilities",profile.observedCapabilities())).toList();
        List<Map<String,Object>> mcpStates=mcp.list(100,null).items().stream().map(server->safeMap("serverId",server.id(),"transport",server.transport(),"status",server.status(),"enabled",server.enabled(),"lastErrorCode",server.lastErrorCode()==null?"NONE":server.lastErrorCode())).toList();
        return new DiagnosticPreview(1,Instant.now().toString(),
                safeMap("appVersion",System.getProperty("agentdesktop.app-version","development"),"channel","internal","unsigned",true),
                safeMap("os",System.getProperty("os.name","unknown"),"arch",System.getProperty("os.arch","unknown"),"jre",System.getProperty("java.version","unknown")),
                providerStates,mcpStates,
                safeMap("schemaVersion",db.schemaVersion(),"integrity",db.integrity(),"threadCount",db.threadCount(),"turnCount",db.turnCount(),"itemCount",db.itemCount(),"turnStatusCounts",db.turnStatusCounts()),
                safeMap("processId",ProcessHandle.current().pid(),"uptimeMs",ManagementFactory.getRuntimeMXBean().getUptime(),
                        "heapUsedBytes",runtime.totalMemory()-runtime.freeMemory(),"heapCommittedBytes",runtime.totalMemory(),
                        "liveThreads",threads.getThreadCount(),"peakThreads",threads.getPeakThreadCount(),"availableProcessors",runtime.availableProcessors(),
                        "activeTurns",turns.activeTurnCount(),"activeThreads",turns.activeThreadCount(),"pendingApprovals",approvals.pendingCount(),
                        "pendingSecretSessions",secretSessions.pendingCount(),"mcpConnections",mcpOperations.activeConnections(),
                        "mcpStdioProcesses",mcpOperations.activeStdioProcesses(),"mcpStderrBuffers",mcpOperations.stderrBuffers(),
                        "sseSubscribers",turns.activeTurnCount(),"skillScriptProcesses",scripts.activeProcessCount(),
                        "ownedChildProcesses",mcpOperations.activeStdioProcesses()+scripts.activeProcessCount()),
                List.of("diagnostic.json"),EXCLUDED,List.of());
    }

    private static Map<String,Object> safeMap(Object...values){var result=new java.util.LinkedHashMap<String,Object>();for(int i=0;i<values.length;i+=2)result.put((String)values[i],values[i+1]);return Map.copyOf(result);}

    public record DiagnosticPreview(int schemaVersion,String generatedAt,Map<String,Object> release,Map<String,Object> platform,
                                    List<Map<String,Object>> providers,List<Map<String,Object>> mcp,Map<String,Object> database,
                                    Map<String,Object> process,List<String> files,List<String> excluded,List<Map<String,Object>> safeEvents){}
}
