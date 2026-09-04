package dev.agentdesktop.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.mcp.McpClientManager;
import dev.agentdesktop.mcp.McpModels;
import dev.agentdesktop.mcp.McpModels.CancellationToken;
import dev.agentdesktop.mcp.McpModels.ToolCallResult;
import dev.agentdesktop.mcp.McpModels.ToolInvocation;
import dev.agentdesktop.runtime.permission.PermissionEngine;
import dev.agentdesktop.runtime.permission.PermissionException;
import dev.agentdesktop.runtime.permission.PermissionFingerprints;
import dev.agentdesktop.runtime.permission.PermissionModels.*;
import dev.agentdesktop.runtime.workspace.FileRootGuard;
import dev.agentdesktop.runtime.workspace.WorkspaceFileAdapter;
import dev.agentdesktop.runtime.skill.SkillScriptAdapter;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stable runtime bridge: providers emit native structured calls; only this gateway executes them. */
public final class ToolGateway {
    static final int MAX_HARNESS_TOOLS = 256;
    private final McpClientManager manager;
    private final PermissionEngine permissions;
    private final ObjectMapper mapper;
    private final FileRootGuard rootGuard;private final WorkspaceFileAdapter files;private final SkillScriptAdapter scripts;
    public ToolGateway(McpClientManager manager,PermissionEngine permissions,ObjectMapper mapper){this(manager,permissions,mapper,null,null,null);}
    public ToolGateway(McpClientManager manager,PermissionEngine permissions,ObjectMapper mapper,FileRootGuard rootGuard,WorkspaceFileAdapter files,SkillScriptAdapter scripts){this.manager=Objects.requireNonNull(manager);this.permissions=Objects.requireNonNull(permissions);this.mapper=Objects.requireNonNull(mapper);this.rootGuard=rootGuard;this.files=files;this.scripts=scripts;}
    /** Compatibility constructor used only by old unit fixtures. Production uses the injected policy engine. */
    public ToolGateway(McpClientManager manager){this(manager,new dev.agentdesktop.runtime.permission.DefaultPermissionEngine(false),new ObjectMapper());}

    public PreparedInvocation prepare(String callId,String threadId,String turnId,String toolId,JsonNode arguments){
        if(toolId.equals("workspace.read_text")||toolId.equals("workspace.write_text"))return prepareWorkspace(callId,threadId,turnId,toolId,arguments);
        if(toolId.equals("workspace.run_script"))return prepareWorkspaceScript(callId,threadId,turnId,arguments);
        if(toolId.equals("skill.run_script"))return prepareScript(callId,threadId,turnId,arguments);
        McpModels.McpServerSummary server=manager.list(100,null).items().stream()
                .filter(item->toolId.startsWith("mcp:"+item.id()+":"))
                .findFirst().orElseThrow(()->new PermissionException("TOOL_DISABLED"));
        McpModels.ToolDescriptor tool=manager.listTools(server.id()).stream().filter(item->item.id().equals(toolId)).findFirst()
                .orElseThrow(()->new PermissionException("TOOL_DISABLED"));
        String hardDeny=null;
        if(!server.enabled()||!"READY".equals(server.status())||!tool.enabled())hardDeny="TOOL_DISABLED";
        CapabilityClass capability=CapabilityClass.valueOf(tool.riskClass().name());
        String sourceFingerprint=sourceFingerprint(server,tool);
        PermissionSubject subject=new PermissionSubject("MCP_TOOL",tool.id(),safeName(tool.displayName()),server.id(),sourceFingerprint);
        ResourceScope resource=new ResourceScope(null,null,origin(server.endpoint()),null,null,null,"mcp:"+server.id()+":"+tool.name());
        String argumentsHash=PermissionFingerprints.argumentsHash(mapper,arguments);
        String invocation=PermissionFingerprints.invocation(threadId,callId,subject,capability,resource,argumentsHash);
        String grant=PermissionFingerprints.threadGrant(threadId,subject,capability,resource);
        SafeInvocationSummary summary=new SafeInvocationSummary(risk(capability),title(capability),
                safeName(tool.displayName())+"（参数字段："+argumentKeys(arguments)+"）",
                Map.of("kind","MCP_TOOL","displayName",safeName(tool.displayName()),"sourceId",server.id()),
                Map.of("serverId",server.id(),"toolId",tool.id()),capability==CapabilityClass.UNKNOWN?"应用无法验证该工具的实际副作用。":"");
        PermissionRequest request=new PermissionRequest(callId,threadId,turnId,capability,subject,resource,argumentsHash,invocation,grant,summary,hardDeny,Instant.now());
        return new PreparedInvocation(new ToolInvocation(callId,threadId,turnId,toolId,arguments.deepCopy(),Instant.now()),request,sourceFingerprint,null,null);
    }
    public PolicyEvaluation evaluate(PreparedInvocation invocation){return permissions.evaluate(invocation.request());}
    public ExecutionPermit permitAllowed(PreparedInvocation invocation,GrantSource source){return permissions.issueAllowed(invocation.request(),source);}
    public ExecutionPermit permit(ResolvedApproval approval){return approval.decision()==ApprovalDecision.ALLOW_THREAD?permissions.allowThread(approval):permissions.allowOnce(approval);}
    public ToolCallResult execute(PreparedInvocation prepared,ExecutionPermit permit,CancellationToken token){
        PreparedInvocation current=prepare(prepared.invocation().callId(),prepared.invocation().threadId(),prepared.invocation().turnId(),prepared.invocation().toolId(),prepared.invocation().arguments());
        if(!current.sourceFingerprint().equals(prepared.sourceFingerprint())||!current.request().invocationFingerprint().equals(prepared.request().invocationFingerprint()))throw new PermissionException("APPROVAL_STALE");
        if(prepared.workspace()!=null){if(files==null)throw new PermissionException("TOOL_DISABLED");WorkspaceTarget target=prepared.workspace();WorkspaceFileAdapter.FileResult result=target.write()?files.write(target.file(),target.content(),target.overwrite(),permit,prepared.request().invocationFingerprint()):files.read(target.file(),permit,prepared.request().invocationFingerprint());var model=mapper.createObjectNode().put("ok",true).put("contentHash",result.contentHash()).put("byteSize",result.byteSize());if(result.modelContent()!=null)model.put("content",result.modelContent());if(target.write()){ProviderEvent.OutputArtifact artifact=artifact(target.file().root().id(),target.file().relativePath(),target.file().target());if(artifact!=null)model.set("artifacts",mapper.valueToTree(List.of(artifact)));}return new ToolCallResult(prepared.invocation().callId(),McpModels.ToolOutcome.SUCCEEDED,model,(target.write()?"Wrote ":"Read ")+result.byteSize()+" bytes ("+result.contentHash()+")",null,Duration.ZERO);}
        if(prepared.script()!=null){if(scripts==null)throw new PermissionException("TOOL_DISABLED");Map<String,FileStamp> before=artifactSnapshot(prepared.script().root().path());SkillScriptAdapter.ScriptResult result=scripts.execute(prepared.script(),permit,prepared.request().invocationFingerprint(),token);var model=mapper.createObjectNode().put("ok",result.succeeded()).put("summary",result.summary());if(result.succeeded()){List<ProviderEvent.OutputArtifact> artifacts=changedArtifacts(prepared.script().root().root().id(),prepared.script().root().path(),before);if(!artifacts.isEmpty())model.set("artifacts",mapper.valueToTree(artifacts));}return new ToolCallResult(prepared.invocation().callId(),result.succeeded()?McpModels.ToolOutcome.SUCCEEDED:McpModels.ToolOutcome.FAILED,model,"Script "+(result.succeeded()?"completed":"failed")+" (stdout "+result.stdoutBytes()+" bytes, stderr "+result.stderrBytes()+" bytes)",result.errorCode(),Duration.ZERO);}
        permit.consume(prepared.request().invocationFingerprint());
        return manager.call(prepared.invocation(),token);
    }
    public ToolCallResult call(String callId,String threadId,String turnId,String toolId,JsonNode arguments,CancellationToken token){
        PreparedInvocation prepared=prepare(callId,threadId,turnId,toolId,arguments);PolicyEvaluation evaluation=evaluate(prepared);
        if(evaluation instanceof Denied denied)return new ToolCallResult(callId,McpModels.ToolOutcome.FAILED,mapper.createObjectNode(),"Tool request denied",denied.errorCode(),Duration.ZERO);
        if(evaluation instanceof ApprovalRequired)return new ToolCallResult(callId,McpModels.ToolOutcome.FAILED,mapper.createObjectNode(),"Approval required","TOOL_APPROVAL_REQUIRED",Duration.ZERO);
        return execute(prepared,permitAllowed(prepared,((Allowed)evaluation).source()),token);
    }

    /** Safe model-facing catalog. Names are deterministic aliases; original tool IDs remain application-owned metadata. */
    public List<HarnessToolBoundary.ToolDefinition> harnessDefinitions(){
        List<HarnessToolBoundary.ToolDefinition> values=new java.util.ArrayList<>();
        values.add(definition("workspace.read_text","Read a UTF-8 text file inside the approved default workspace. Use rootId root_default and a relative path whose parent already exists.",Map.of("type","object","properties",Map.of("rootId",defaultRootParameter(),"relativePath",Map.of("type","string","description","Relative path inside the approved root; its parent directory must already exist")),"required",List.of("rootId","relativePath"),"additionalProperties",false)));
        values.add(definition("workspace.write_text","Write UTF-8 text inside the approved default workspace after policy evaluation. Use rootId root_default; for generated report scripts use a filename directly at the workspace root.",Map.of("type","object","properties",Map.of("rootId",defaultRootParameter(),"relativePath",Map.of("type","string","description","Relative path inside the approved root; its parent directory must already exist"),"content",Map.of("type","string"),"overwrite",Map.of("type","boolean","default",false)),"required",List.of("rootId","relativePath","content"),"additionalProperties",false)));
        values.add(definition("workspace.run_script","Run a previously approved and SHA-256 pinned workspace .py or .js script with the application-selected interpreter. Use rootId root_default.",Map.of("type","object","properties",Map.of("rootId",defaultRootParameter(),"relativePath",Map.of("type","string"),"contentHash",Map.of("type","string","pattern","^sha256:[0-9a-f]{64}$"),"arguments",Map.of("type","array","items",Map.of("type","string"),"maxItems",32)),"required",List.of("rootId","relativePath","contentHash","arguments"),"additionalProperties",false)));
        values.add(definition("skill.run_script","Run a fixed .ps1, .py, or .js script resource from an activated Skill inside the approved default workspace. Use workingRootId root_default.",Map.of("type","object","properties",Map.of("skillId",Map.of("type","string"),"resourceId",Map.of("type","string"),"workingRootId",defaultRootParameter(),"arguments",Map.of("type","array","items",Map.of("type","string"),"maxItems",32)),"required",List.of("skillId","resourceId","workingRootId","arguments"),"additionalProperties",false)));
        for(McpModels.McpServerSummary server:manager.list(100,null).items()){
            if(!server.enabled()||!"READY".equals(server.status()))continue;
            for(McpModels.ToolDescriptor tool:manager.listTools(server.id())){
                if(!tool.enabled()||tool.inputSchema()==null||!tool.inputSchema().isObject())continue;
                @SuppressWarnings("unchecked") Map<String,Object> schema=mapper.convertValue(tool.inputSchema(),Map.class);
                values.add(definition(tool.id(),mcpDescription(server,tool),schema));
            }
        }
        if(values.size()>MAX_HARNESS_TOOLS)throw new PermissionException("TOOL_CATALOG_TOO_LARGE");
        return List.copyOf(values);
    }

    private static HarnessToolBoundary.ToolDefinition definition(String toolId,String description,Map<String,Object> parameters){
        String alias="app_tool_"+PermissionFingerprints.sha256(toolId).substring("sha256:".length(),"sha256:".length()+24);
        return new HarnessToolBoundary.ToolDefinition(alias,toolId,description,Map.copyOf(parameters));
    }
    private static Map<String,Object> defaultRootParameter(){return Map.of("type","string","const",FileRootGuard.DEFAULT_ROOT_ID,"description","Application-approved default workspace root ID");}

    private static String mcpDescription(McpModels.McpServerSummary server,McpModels.ToolDescriptor tool){
        String identity="MCP "+safeName(server.name())+"/"+safeName(tool.name());String description=safeName(tool.description());
        return description.equals("工具")||description.isBlank()?identity:identity+". "+description;
    }

    private PreparedInvocation prepareWorkspace(String callId,String threadId,String turnId,String toolId,JsonNode arguments){if(rootGuard==null||arguments==null||!arguments.isObject()||!arguments.path("rootId").isTextual()||!arguments.path("relativePath").isTextual())throw new PermissionException("TOOL_ARGUMENTS_INVALID");String rootId=arguments.path("rootId").textValue(),relative=arguments.path("relativePath").textValue();boolean write=toolId.endsWith("write_text");if(write&&(!arguments.path("content").isTextual()||(arguments.has("overwrite")&&!arguments.path("overwrite").isBoolean())))throw new PermissionException("TOOL_ARGUMENTS_INVALID");FileRootGuard.ResolvedFile file=rootGuard.resolve(rootId,relative,write);String content=write?arguments.path("content").textValue():null;boolean overwrite=write&&arguments.path("overwrite").asBoolean(false);CapabilityClass capability=write?CapabilityClass.WRITE:CapabilityClass.READ;String sourceFingerprint=file.scopeFingerprint();PermissionSubject subject=new PermissionSubject("WORKSPACE_FILE",toolId,toolId,file.root().id(),sourceFingerprint);ResourceScope resource=new ResourceScope(file.root().id(),relative,null,null,null,write?PermissionFingerprints.sha256(content):null,"file:"+file.root().id()+":"+file.root().mode()+":"+capability);String argsHash=PermissionFingerprints.argumentsHash(mapper,arguments);String invocation=PermissionFingerprints.invocation(threadId,callId,subject,capability,resource,argsHash);String grant=PermissionFingerprints.threadGrant(threadId,subject,capability,resource);SafeInvocationSummary summary=new SafeInvocationSummary(write?"high":"medium",write?"允许写入工作区文件？":"允许读取工作区文件？",toolId+" 将访问 "+file.root().label()+" / "+relative,Map.of("kind","WORKSPACE_FILE","displayName",file.root().label()),Map.of("rootId",file.root().id(),"displayPath",file.root().label()+" / "+relative),"");PermissionRequest request=new PermissionRequest(callId,threadId,turnId,capability,subject,resource,argsHash,invocation,grant,summary,null,Instant.now());return new PreparedInvocation(new ToolInvocation(callId,threadId,turnId,toolId,arguments.deepCopy(),Instant.now()),request,sourceFingerprint,new WorkspaceTarget(file,write,content,overwrite),null);}
    private PreparedInvocation prepareScript(String callId,String threadId,String turnId,JsonNode arguments){if(scripts==null||arguments==null||!arguments.isObject()||!arguments.path("arguments").isArray())throw new PermissionException("TOOL_ARGUMENTS_INVALID");List<String> values=new java.util.ArrayList<>();arguments.path("arguments").forEach(item->{if(!item.isTextual())throw new PermissionException("TOOL_ARGUMENTS_INVALID");values.add(item.textValue());});SkillScriptAdapter.PreparedScript script=scripts.prepare(turnId,arguments.path("skillId").asText(null),arguments.path("resourceId").asText(null),arguments.path("workingRootId").asText(null),values);String sourceFingerprint=PermissionFingerprints.sha256(script.plan().skillId()+"\0"+script.plan().resourceId()+"\0"+script.plan().contentHash()+"\0"+script.plan().interpreterProfile()+"\0"+script.root().scopeFingerprint());PermissionSubject subject=new PermissionSubject("SKILL_SCRIPT","skill.run_script",script.plan().skillName(),script.plan().skillId(),sourceFingerprint);ResourceScope resource=new ResourceScope(script.root().root().id(),null,null,script.plan().skillId(),script.plan().resourceId(),script.plan().contentHash(),"script:"+script.plan().skillId()+":"+script.plan().resourceId()+":"+script.plan().contentHash()+":"+script.plan().interpreterProfile());String argsHash=PermissionFingerprints.argumentsHash(mapper,arguments);String invocation=PermissionFingerprints.invocation(threadId,callId,subject,CapabilityClass.EXECUTE,resource,argsHash);String grant=PermissionFingerprints.threadGrant(threadId,subject,CapabilityClass.EXECUTE,resource);SafeInvocationSummary summary=new SafeInvocationSummary("critical","允许运行 Skill 脚本？","skill.run_script 将运行 "+script.plan().skillName()+" 的已固定脚本",Map.of("kind","SKILL_SCRIPT","displayName",script.plan().skillName()),Map.of("skillId",script.plan().skillId(),"resourceId",script.plan().resourceId(),"rootId",script.root().root().id()),"脚本来源不可信，将以当前 Windows 用户权限运行；文件根不是操作系统沙箱。");PermissionRequest request=new PermissionRequest(callId,threadId,turnId,CapabilityClass.EXECUTE,subject,resource,argsHash,invocation,grant,summary,null,Instant.now());return new PreparedInvocation(new ToolInvocation(callId,threadId,turnId,"skill.run_script",arguments.deepCopy(),Instant.now()),request,sourceFingerprint,null,script);}
    private PreparedInvocation prepareWorkspaceScript(String callId,String threadId,String turnId,JsonNode arguments){if(scripts==null||arguments==null||!arguments.isObject()||!arguments.path("arguments").isArray())throw new PermissionException("TOOL_ARGUMENTS_INVALID");List<String> values=new java.util.ArrayList<>();arguments.path("arguments").forEach(item->{if(!item.isTextual())throw new PermissionException("TOOL_ARGUMENTS_INVALID");values.add(item.textValue());});SkillScriptAdapter.PreparedScript script=scripts.prepareWorkspace(turnId,arguments.path("rootId").asText(null),arguments.path("relativePath").asText(null),arguments.path("contentHash").asText(null),values);String sourceFingerprint=PermissionFingerprints.sha256(script.plan().resourceId()+"\0"+script.plan().contentHash()+"\0"+script.plan().interpreterProfile()+"\0"+script.root().scopeFingerprint());PermissionSubject subject=new PermissionSubject("WORKSPACE_SCRIPT","workspace.run_script","工作区脚本",script.root().root().id(),sourceFingerprint);ResourceScope resource=new ResourceScope(script.root().root().id(),script.plan().resourceId(),null,null,null,script.plan().contentHash(),"workspace-script:"+script.plan().contentHash()+":"+script.plan().interpreterProfile());String argsHash=PermissionFingerprints.argumentsHash(mapper,arguments);String invocation=PermissionFingerprints.invocation(threadId,callId,subject,CapabilityClass.EXECUTE,resource,argsHash);String grant=PermissionFingerprints.threadGrant(threadId,subject,CapabilityClass.EXECUTE,resource);SafeInvocationSummary summary=new SafeInvocationSummary("critical","允许运行工作区脚本？","workspace.run_script 将运行已固定哈希的 "+script.plan().resourceId(),Map.of("kind","WORKSPACE_SCRIPT","displayName","工作区脚本"),Map.of("rootId",script.root().root().id(),"displayPath",script.plan().resourceId()),"脚本来源不可信，将以当前 Windows 用户权限运行；文件根不是操作系统沙箱。");PermissionRequest request=new PermissionRequest(callId,threadId,turnId,CapabilityClass.EXECUTE,subject,resource,argsHash,invocation,grant,summary,null,Instant.now());return new PreparedInvocation(new ToolInvocation(callId,threadId,turnId,"workspace.run_script",arguments.deepCopy(),Instant.now()),request,sourceFingerprint,null,script);}
    public record PreparedInvocation(ToolInvocation invocation,PermissionRequest request,String sourceFingerprint,WorkspaceTarget workspace,SkillScriptAdapter.PreparedScript script){}
    public record WorkspaceTarget(FileRootGuard.ResolvedFile file,boolean write,String content,boolean overwrite){}
    private String sourceFingerprint(McpModels.McpServerSummary server,McpModels.ToolDescriptor tool){String schema=PermissionFingerprints.argumentsHash(mapper,tool.inputSchema());return PermissionFingerprints.sha256(String.join("\0",server.id(),server.transport(),safe(server.endpoint()),safe(server.executable()),String.valueOf(server.catalogVersion()),tool.name(),schema));}
    private static String origin(String endpoint){try{if(endpoint==null)return "";URI uri=URI.create(endpoint);return uri.getScheme()+"://"+uri.getHost()+(uri.getPort()<0?"":":"+uri.getPort());}catch(Exception ignored){return "";}}
    private static String argumentKeys(JsonNode args){if(args==null||!args.isObject())return "无";List<String> keys=new java.util.ArrayList<>();args.fieldNames().forEachRemaining(name->{if(keys.size()<8)keys.add(redactKey(name));});return keys.isEmpty()?"无":String.join("、",keys);}
    private static String redactKey(String key){return key.matches("(?i).*(token|secret|password|authorization|cookie|content|body).*")?"[敏感字段]":safeName(key);}
    private static String safeName(String value){if(value==null)return "工具";String clean=value.replaceAll("[\\p{Cntrl}]"," ").strip();return clean.substring(0,Math.min(clean.length(),120));}
    private static String safe(String value){return value==null?"":value;}
    private static String risk(CapabilityClass value){return switch(value){case DEMO_SAFE->"low";case READ->"medium";case WRITE,NETWORK->"high";case EXECUTE,UNKNOWN->"critical";};}
    private static String title(CapabilityClass value){return switch(value){case READ->"允许读取数据？";case WRITE->"允许写入数据？";case NETWORK->"允许网络操作？";case EXECUTE->"允许执行外部能力？";case UNKNOWN->"允许未知能力执行？";case DEMO_SAFE->"允许测试工具？";};}

    private static Map<String,FileStamp> artifactSnapshot(Path root){
        Map<String,FileStamp> values=new LinkedHashMap<>();
        try(var stream=Files.find(root,6,(file,attributes)->attributes.isRegularFile()&&supportedArtifact(file))){
            stream.limit(512).forEach(file->{try{String relative=portable(root.relativize(file));values.put(relative,new FileStamp(Files.size(file),Files.getLastModifiedTime(file).toInstant()));}catch(Exception ignored){}});
        }catch(Exception ignored){}
        return values;
    }
    private static List<ProviderEvent.OutputArtifact> changedArtifacts(String rootId,Path root,Map<String,FileStamp> before){
        List<ProviderEvent.OutputArtifact> result=new java.util.ArrayList<>();
        try(var stream=Files.find(root,6,(file,attributes)->attributes.isRegularFile()&&supportedArtifact(file))){
            stream.limit(512).forEach(file->{try{String relative=portable(root.relativize(file));FileStamp current=new FileStamp(Files.size(file),Files.getLastModifiedTime(file).toInstant());if(!current.equals(before.get(relative))){ProviderEvent.OutputArtifact artifact=artifact(rootId,relative,file);if(artifact!=null)result.add(artifact);}}catch(Exception ignored){}});
        }catch(Exception ignored){}
        return List.copyOf(result);
    }
    private static ProviderEvent.OutputArtifact artifact(String rootId,String relativePath,Path file){
        if(!supportedArtifact(file))return null;
        try{return new ProviderEvent.OutputArtifact(rootId,portable(Path.of(relativePath)),file.getFileName().toString(),mediaType(file),Files.size(file));}catch(Exception ignored){return null;}
    }
    static boolean supportedArtifact(Path file){String name=file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);return name.endsWith(".docx")||name.endsWith(".pptx")||name.endsWith(".xlsx")||name.endsWith(".xls")||name.endsWith(".html")||name.endsWith(".htm")||name.endsWith(".md")||name.endsWith(".markdown");}
    static String mediaType(Path file){String name=file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);if(name.endsWith(".docx"))return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";if(name.endsWith(".pptx"))return "application/vnd.openxmlformats-officedocument.presentationml.presentation";if(name.endsWith(".xlsx"))return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";if(name.endsWith(".xls"))return "application/vnd.ms-excel";if(name.endsWith(".html")||name.endsWith(".htm"))return "text/html";return "text/markdown";}
    private static String portable(Path relative){return relative.toString().replace('\\','/');}
    private record FileStamp(long size,Instant modifiedAt){}
}
