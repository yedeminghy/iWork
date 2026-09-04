package dev.agentdesktop.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.mcp.McpModels.*;
import dev.agentdesktop.persistence.Ids;
import dev.agentdesktop.persistence.McpCatalogRepository;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.McpHttpClientTransportAuthorizationException;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SdkMcpClientManager implements McpClientManager {
    private final McpCatalogRepository repository; private final ObjectMapper mapper; private final Path workspace;
    private final boolean fixtures; private final Duration requestTimeout; private final ResultSanitizer sanitizer; private final Map<String,Connection> connections=new ConcurrentHashMap<>();
    private final Map<String,ArrayDeque<String>> stderr=new ConcurrentHashMap<>();
    public SdkMcpClientManager(McpCatalogRepository repository,ObjectMapper mapper,Path workspace,boolean fixtures){this(repository,mapper,workspace,fixtures,Duration.ofSeconds(30));}
    SdkMcpClientManager(McpCatalogRepository repository,ObjectMapper mapper,Path workspace,boolean fixtures,Duration requestTimeout){this.repository=repository;this.mapper=mapper;this.workspace=workspace.toAbsolutePath().normalize();this.fixtures=fixtures;this.requestTimeout=requestTimeout;this.sanitizer=new ResultSanitizer(mapper);}

    @Override public McpServerSummary save(McpServerDraft draft){
        McpDraftValidator.validate(draft,workspace,fixtures); String id=draft.id()==null||draft.id().isBlank()?Ids.newId("mcp"):draft.id();
        if(!id.matches("mcp_[A-Za-z0-9-]{8,}"))throw new McpException("MCP_CONFIG_INVALID","Invalid MCP server identifier");
        String now=Instant.now().toString(); McpCatalogRepository.ServerRow existing=null;try{existing=repository.get(id);}catch(McpCatalogRepository.DomainException ignored){}
        McpCatalogRepository.ServerRow row;
        if(draft instanceof StdioServerDraft s) row=new McpCatalogRepository.ServerRow(id,s.name().strip(),"STDIO",s.enabled()?"CONFIGURED":"DISABLED",s.enabled(),null,
                Path.of(s.executable()).toAbsolutePath().normalize().toString(),List.copyOf(s.arguments()),(s.workingDirectory()==null?workspace:s.workingDirectory()).toAbsolutePath().normalize().toString(),
                List.copyOf(s.allowedEnvironmentNames()),null,policyJson(s.reconnectPolicy()),null,null,null,existing==null?0:existing.catalogVersion(),existing==null?now:existing.createdAt(),now);
        else {StreamableHttpServerDraft h=(StreamableHttpServerDraft)draft;row=new McpCatalogRepository.ServerRow(id,h.name().strip(),"STREAMABLE_HTTP",h.enabled()?"CONFIGURED":"DISABLED",h.enabled(),h.endpoint().toString(),null,List.of(),null,List.of(),h.credentialRef(),policyJson(h.reconnectPolicy()),null,null,null,existing==null?0:existing.catalogVersion(),existing==null?now:existing.createdAt(),now);}
        if(existing!=null)disconnect(id);return summary(repository.save(row));
    }

    @Override public McpServerSummary connect(String id,EphemeralCredential credential){
        McpCatalogRepository.ServerRow row=repository.get(id);if(!row.enabled())throw new McpException("TOOL_DISABLED","MCP server is disabled");disconnect(id);
        repository.updateStatus(id,"STARTING",null,null,null,row.catalogVersion());
        int attempts=policy(row).maxAttempts()+1;Throwable last=null;
        EphemeralCredential safeCredential=credential==null?EphemeralCredential.empty():credential;
        for(int attempt=1;attempt<=attempts;attempt++){Connection connection=null;try{
            connection=open(row,safeCredential);connection.client.initialize().block(Duration.ofSeconds(10));
            long version=row.catalogVersion()+1;var listed=connection.client.listTools().block(Duration.ofSeconds(10));if(listed==null)throw new McpException("MCP_TIMEOUT","Tool catalog timed out");List<ToolDescriptor> tools=descriptors(id,version,listed.tools());persistTools(id,version,tools);
            connections.put(id,connection);return summary(repository.updateStatus(id,"READY",null,"Connected",Instant.now(),version));
        }catch(Exception e){last=e;closeConnection(connection);if(!retryable(e)||attempt==attempts)break;sleep(backoff(policy(row),attempt));}}
        String code=errorCode(row,last);String diagnostic=safeDiagnostic(last);repository.updateStatus(id,"FAILED",code,diagnostic,null,row.catalogVersion());throw new McpException(code,"MCP connection failed",last);
    }

    @Override public void disconnect(String id){Connection connection=connections.remove(id);closeConnection(connection);try{var row=repository.get(id);repository.updateStatus(id,row.enabled()?"CONFIGURED":"DISABLED",null,"Disconnected",null,row.catalogVersion());}catch(Exception ignored){}}
    @Override public void delete(String id){repository.get(id);disconnect(id);repository.softDelete(id);stderr.remove(id);}
    @Override public McpServerPage list(int limit,String cursor){if(cursor!=null&&!cursor.isBlank())throw new McpException("MCP_CONFIG_INVALID","Invalid cursor");return new McpServerPage(repository.list(limit).stream().map(SdkMcpClientManager::summary).toList(),null);}
    @Override public List<ToolDescriptor> listTools(String id){repository.get(id);return repository.listTools(id).stream().map(this::descriptor).toList();}

    @Override public ToolCallResult call(ToolInvocation invocation,CancellationToken cancellation){
        Instant start=Instant.now();ToolDescriptor tool;
        try { tool=findTool(invocation.toolId()); }
        catch(McpException e) { return failure(invocation,e.code(),start); }
        if(!tool.enabled())return failure(invocation,"TOOL_DISABLED",start);
        if(cancellation!=null&&cancellation.isCancellationRequested())return failure(invocation,"TOOL_CANCELLED",start);
        try{
            validateArguments(tool.inputSchema(),invocation.arguments());Connection connection=connections.get(tool.serverId());if(connection==null)return failure(invocation,"TOOL_DISABLED",start);
            Map<String,Object> arguments=mapper.convertValue(invocation.arguments(),new TypeReference<>(){});
            McpSchema.CallToolResult result;
            try { result=awaitTool(connection,tool.name(),arguments,cancellation); }
            catch(Exception firstFailure){
                if(cancellation!=null&&cancellation.isCancellationRequested()){disconnect(tool.serverId());return failure(invocation,"TOOL_CANCELLED",start);}
                if(!retryable(firstFailure))throw firstFailure;
                EphemeralCredential credential=connection.credential();
                connections.remove(tool.serverId(),connection);closeConnection(connection);
                var failedRow=repository.get(tool.serverId());
                repository.updateStatus(tool.serverId(),"FAILED",errorCode(failedRow,firstFailure),safeDiagnostic(firstFailure),null,failedRow.catalogVersion());
                // Never replay an ambiguous tool call: the server may have applied
                // side effects. The explicit retry action or app restart reconnects.
                throw firstFailure;
            }
            Object model=result.structuredContent()!=null?result.structuredContent():content(result.content());String summary=sanitizer.summary(model);
            if(Boolean.TRUE.equals(result.isError()))return new ToolCallResult(invocation.callId(),ToolOutcome.FAILED,sanitizer.modelResult(model),summary,"MCP_TOOL_ERROR",Duration.between(start,Instant.now()));
            return new ToolCallResult(invocation.callId(),ToolOutcome.SUCCEEDED,sanitizer.modelResult(model),summary,null,Duration.between(start,Instant.now()));
        }catch(McpException e){return new ToolCallResult(invocation.callId(),ToolOutcome.FAILED,mapper.nullNode(),e.getMessage(),e.code(),Duration.between(start,Instant.now()));}
        catch(Exception e){String code=errorCode(repository.get(tool.serverId()),e);Connection failed=connections.remove(tool.serverId());closeConnection(failed);var row=repository.get(tool.serverId());repository.updateStatus(tool.serverId(),"FAILED",code,safeDiagnostic(e),null,row.catalogVersion());return new ToolCallResult(invocation.callId(),ToolOutcome.FAILED,mapper.nullNode(),"Tool call failed",code,Duration.between(start,Instant.now()));}
    }
    @Override public McpDiagnostic recentDiagnostic(String id){var row=repository.get(id);return new McpDiagnostic(id,row.status(),row.lastErrorCode(),row.lastDiagnostic(),List.copyOf(stderr.getOrDefault(id,new ArrayDeque<>())));}
    @Override public OperationalStats operationalStats(){
        int stdio=(int)connections.values().stream().filter(connection->connection.stdio()!=null&&!connection.stdio().hasExited()).count();
        return new OperationalStats(connections.size(),stdio,stderr.size());
    }
    @Override public void close(){new ArrayList<>(connections.keySet()).forEach(this::disconnect);}

    private Connection open(McpCatalogRepository.ServerRow row,EphemeralCredential credential){
        McpClientTransport transport;
        if("STDIO".equals(row.transport())){
            Map<String,String> environment=new LinkedHashMap<>();for(String name:row.allowedEnvironmentNames())if(credential.environment().containsKey(name))environment.put(name,credential.environment().get(name));
            ServerParameters params=ServerParameters.builder(row.executable()).args(row.arguments()).env(environment).build();StdioClientTransport stdio=new RestrictedStdioClientTransport(params,McpJsonDefaults.getMapper(),Path.of(row.workingDirectory()),environment);stdio.setStdErrorHandler(line->recordStderr(row.id(),line));transport=stdio;
        }else{
            URI uri=URI.create(row.endpoint());String base=uri.getScheme()+"://"+uri.getAuthority();String endpoint=uri.getRawPath()==null||uri.getRawPath().isBlank()?"/":uri.getRawPath();if(uri.getRawQuery()!=null)endpoint+="?"+uri.getRawQuery();
            var builder=HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint).connectTimeout(Duration.ofSeconds(10)).customizeClient(client->client.followRedirects(HttpClient.Redirect.NEVER));
            if(credential.bearerToken()!=null&&!credential.bearerToken().isBlank())builder.httpRequestCustomizer((request,method,target,body,context)->request.header("Authorization","Bearer "+credential.bearerToken()));transport=builder.build();
        }
        return new Connection(McpClient.async(transport).requestTimeout(Duration.ofSeconds(30)).initializationTimeout(Duration.ofSeconds(10)).build(),credential,transport instanceof RestrictedStdioClientTransport stdio?stdio:null);
    }

    private McpSchema.CallToolResult awaitTool(Connection connection,String name,Map<String,Object> arguments,CancellationToken cancellation)throws Exception{
        if(connection==null)throw new McpException("TOOL_DISABLED","Tool connection is unavailable");
        var future=connection.client.callTool(new McpSchema.CallToolRequest(name,arguments)).toFuture();long deadline=System.nanoTime()+requestTimeout.toNanos();
        for(;;){
            if(cancellation!=null&&cancellation.isCancellationRequested()){future.cancel(true);throw new McpException("TOOL_CANCELLED","Tool call was cancelled");}
            if(connection.stdio()!=null&&connection.stdio().hasExited()){future.cancel(true);throw new McpException("MCP_PROCESS_EXITED","STDIO MCP process exited");}
            if(System.nanoTime()>=deadline){future.cancel(true);throw new McpException("MCP_TIMEOUT","Tool call timed out");}
            try{return future.get(50,TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){}
        }
    }

    private static void closeConnection(Connection connection){if(connection!=null)try{connection.client.closeGracefully().block(Duration.ofSeconds(5));}catch(Exception ignored){}}

    private List<ToolDescriptor> descriptors(String server,long version,List<McpSchema.Tool> tools){List<ToolDescriptor> result=new ArrayList<>();for(McpSchema.Tool tool:tools){JsonNode schema=mapper.valueToTree(tool.inputSchema());String description=tool.description()==null?"":tool.description();if(description.getBytes().length>4096||schema.toString().getBytes().length>32768||depth(schema)>16||properties(schema)>128)continue;RiskClass risk=(fixtures&&("echo".equals(tool.name())||"sum".equals(tool.name())))?RiskClass.DEMO_SAFE:RiskClass.UNKNOWN;result.add(new ToolDescriptor("mcp:"+server+":"+tool.name(),server,tool.name(),tool.title()==null?tool.name():tool.title(),description,schema,risk,true,null));}return result;}
    private void persistTools(String id,long version,List<ToolDescriptor> tools){String now=Instant.now().toString();repository.replaceTools(id,version,tools.stream().map(t->new McpCatalogRepository.ToolRow(t.id(),id,t.name(),t.description(),t.inputSchema().toString(),t.riskClass().name(),t.enabled(),version,now,now)).toList());}
    private ToolDescriptor descriptor(McpCatalogRepository.ToolRow row){try{return new ToolDescriptor(row.id(),row.serverId(),row.name(),row.name(),row.description(),mapper.readTree(row.inputSchemaJson()),RiskClass.valueOf(row.riskClass()),row.enabled(),row.enabled()?null:"TOOL_DISABLED");}catch(Exception e){throw new McpException("MCP_PROTOCOL_MISMATCH","Invalid cached tool schema",e);}}
    private ToolDescriptor findTool(String id){return repository.list(100).stream().flatMap(s->listTools(s.id()).stream()).filter(t->t.id().equals(id)).findFirst().orElseThrow(()->new McpException("TOOL_DISABLED","Tool is not available"));}
    private void validateArguments(JsonNode schema,JsonNode args){if(args==null||!args.isObject())throw new McpException("TOOL_ARGUMENT_INVALID","Tool arguments must be an object");JsonNode required=schema.path("required");if(required.isArray())for(JsonNode name:required)if(!args.has(name.asText()))throw new McpException("TOOL_ARGUMENT_INVALID","Required tool argument is missing");JsonNode properties=schema.path("properties");if(properties.isObject())properties.fields().forEachRemaining(entry->{JsonNode value=args.get(entry.getKey());if(value!=null&&!matches(value,entry.getValue().path("type").asText()))throw new McpException("TOOL_ARGUMENT_INVALID","Tool argument has an invalid type");});}
    private static boolean matches(JsonNode value,String type){return switch(type){case "string"->value.isTextual();case "number"->value.isNumber();case "integer"->value.isIntegralNumber();case "boolean"->value.isBoolean();case "array"->value.isArray();case "object"->value.isObject();default->true;};}
    private ToolCallResult failure(ToolInvocation i,String code,Instant start){return new ToolCallResult(i.callId(),"TOOL_CANCELLED".equals(code)?ToolOutcome.CANCELLED:ToolOutcome.FAILED,mapper.nullNode(),code,code,Duration.between(start,Instant.now()));}
    private Object content(List<McpSchema.Content> content){return content.stream().map(item->item instanceof McpSchema.TextContent text?text.text():item.toString()).toList();}
    private void recordStderr(String id,String raw){ArrayDeque<String> lines=stderr.computeIfAbsent(id,key->new ArrayDeque<>());synchronized(lines){String line=ResultSanitizer.redact(raw);if(line.length()>2048)line=line.substring(0,2048);if(lines.size()==200)lines.removeFirst();lines.addLast(line);}}
    private ReconnectPolicy policy(McpCatalogRepository.ServerRow row){try{JsonNode value=mapper.readTree(row.reconnectPolicyJson());return new ReconnectPolicy(value.path("maxAttempts").asInt(3),Duration.ofMillis(value.path("initialBackoffMs").asLong(250)),Duration.ofMillis(value.path("maxBackoffMs").asLong(2000)));}catch(Exception e){return ReconnectPolicy.defaults();}}
    private static Duration backoff(ReconnectPolicy p,int attempt){long value=Math.min(p.maxBackoff().toMillis(),p.initialBackoff().toMillis()*(1L<<Math.min(attempt-1,20)));return Duration.ofMillis(value);}
    private static void sleep(Duration duration){try{Thread.sleep(duration);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    private static boolean retryable(Throwable e){String text=causeText(e);return authStatus(e)==null&&!text.contains("401")&&!text.contains("403")&&!text.contains("TOOL_CANCELLED");}
    private static String errorCode(McpCatalogRepository.ServerRow row,Throwable e){String text=causeText(e);if(authStatus(e)!=null||text.contains("401")||text.contains("403"))return "MCP_AUTH_FAILED";if(text.toLowerCase().contains("timeout"))return "MCP_TIMEOUT";if("STDIO".equals(row.transport())&&(text.toLowerCase().contains("closed")||text.toLowerCase().contains("exit")||text.toLowerCase().contains("broken pipe")))return "MCP_PROCESS_EXITED";return "STDIO".equals(row.transport())?"MCP_START_FAILED":"MCP_CONNECT_FAILED";}
    private static Integer authStatus(Throwable error){for(Throwable current=error;current!=null;current=current.getCause())if(current instanceof McpHttpClientTransportAuthorizationException auth)return auth.getResponseInfo().statusCode();return null;}
    private static String causeText(Throwable error){StringBuilder value=new StringBuilder();for(Throwable current=error;current!=null;current=current.getCause())value.append(' ').append(Objects.toString(current.getMessage(),""));return value.toString();}
    private static String safeDiagnostic(Throwable error){if(error==null)return "Connection failed";String value=error.getClass().getSimpleName()+": "+ResultSanitizer.redact(Objects.toString(error.getMessage(),"Connection failed"));return value.length()>512?value.substring(0,512):value;}
    private static int depth(JsonNode node){if(!node.isContainerNode())return 0;int max=0;for(JsonNode child:node)max=Math.max(max,depth(child));return max+1;}
    private static int properties(JsonNode node){int count=node.path("properties").isObject()?node.path("properties").size():0;for(JsonNode child:node)count+=properties(child);return count;}
    private static McpServerSummary summary(McpCatalogRepository.ServerRow r){return new McpServerSummary(r.id(),r.name(),r.transport(),r.status(),r.enabled(),r.endpoint(),r.executable(),r.arguments(),r.workingDirectory(),r.allowedEnvironmentNames(),r.credentialRef(),r.lastErrorCode(),r.lastConnectedAt(),r.catalogVersion());}
    private String json(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new McpException("MCP_CONFIG_INVALID","Cannot serialize MCP configuration",e);}}
    private String policyJson(ReconnectPolicy policy){return json(Map.of("maxAttempts",policy.maxAttempts(),"initialBackoffMs",policy.initialBackoff().toMillis(),"maxBackoffMs",policy.maxBackoff().toMillis()));}
    private record Connection(McpAsyncClient client,EphemeralCredential credential,RestrictedStdioClientTransport stdio){}
}
