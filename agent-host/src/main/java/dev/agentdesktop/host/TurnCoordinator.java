package dev.agentdesktop.host;

import dev.agentdesktop.host.api.AgUiEvent;
import dev.agentdesktop.host.api.RunAgentInput;
import dev.agentdesktop.persistence.ConversationRepository;
import dev.agentdesktop.persistence.ConversationStore;
import dev.agentdesktop.persistence.Ids;
import dev.agentdesktop.runtime.ChatRequest;
import dev.agentdesktop.runtime.ModelProvider;
import dev.agentdesktop.runtime.ProviderEvent;
import dev.agentdesktop.runtime.ToolGateway;
import dev.agentdesktop.runtime.HarnessRunService;
import dev.agentdesktop.runtime.HarnessToolBoundary;
import dev.agentdesktop.runtime.provider.ProviderRegistry;
import dev.agentdesktop.runtime.permission.PermissionException;
import dev.agentdesktop.runtime.permission.PermissionModels.*;
import dev.agentdesktop.runtime.skill.AgentScopeSkillAdapter;
import dev.agentdesktop.runtime.skill.SkillHarnessBoundary;
import dev.agentdesktop.runtime.skill.SkillException;
import dev.agentdesktop.runtime.skill.SkillModels.*;
import dev.agentdesktop.mcp.McpClientManager;
import dev.agentdesktop.mcp.McpModels;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public final class TurnCoordinator {
    private static final int MAX_HISTORY_MESSAGES=40;
    private static final int MAX_HISTORY_CHARACTERS=64_000;
    private static final Logger LOGGER=LoggerFactory.getLogger(TurnCoordinator.class);
    private final ConversationRepository repository;
    private final ModelProvider provider;
    private final HarnessRunService harness;
    private final ProviderSecretSessionStore secretSessions;
    private final ProviderRegistry providerRegistry;
    private final ToolGateway toolGateway;
    private final AgentScopeSkillAdapter skillAdapter;
    private final ApprovalCoordinator approvals;
    private final Map<String, ActiveTurn> activeTurns = new ConcurrentHashMap<>();
    private final Set<String> activeThreads = ConcurrentHashMap.newKeySet();

    @Autowired
    public TurnCoordinator(ConversationRepository repository, ModelProvider fixtureProvider,HarnessRunService harness,ProviderSecretSessionStore secretSessions,
                           ProviderRegistry providerRegistry,ToolGateway toolGateway, AgentScopeSkillAdapter skillAdapter,ApprovalCoordinator approvals) {
        this.repository = repository;
        boolean fixtures=Boolean.getBoolean("agentdesktop.test-fixtures");
        this.provider = fixtures?fixtureProvider:null;
        this.harness=fixtures?null:harness;
        this.secretSessions=secretSessions;
        this.providerRegistry=providerRegistry;
        this.toolGateway = toolGateway;
        this.skillAdapter = skillAdapter;
        this.approvals=approvals;
    }

    TurnCoordinator(ConversationRepository repository, ModelProvider provider) {
        this.repository=repository;this.provider=provider;this.harness=null;this.secretSessions=null;this.providerRegistry=null;
        this.toolGateway=new ToolGateway(new DisabledMcpClientManager());this.skillAdapter=null;this.approvals=null;
    }

    private static final class DisabledMcpClientManager implements McpClientManager {
        public McpModels.McpServerSummary save(McpModels.McpServerDraft draft){throw new UnsupportedOperationException();}
        public McpModels.McpServerSummary connect(String id,McpModels.EphemeralCredential c){throw new UnsupportedOperationException();}
        public void disconnect(String id){} public void delete(String id){} public McpModels.McpServerPage list(int l,String c){return new McpModels.McpServerPage(java.util.List.of(),null);}
        public java.util.List<McpModels.ToolDescriptor> listTools(String id){return java.util.List.of();}
        public McpModels.ToolCallResult call(McpModels.ToolInvocation i,McpModels.CancellationToken c){throw new UnsupportedOperationException();}
        public McpModels.McpDiagnostic recentDiagnostic(String id){throw new UnsupportedOperationException();} public void close(){}
    }

    public Flux<AgUiEvent> run(RunAgentInput input) {
        return Flux.defer(() -> {
            ProviderSecretSessionStore.ConsumedSecret providerSecret=null;
            if(provider!=null){if (!provider.id().equals(input.context().modelProfileId())) return Flux.error(new dev.agentdesktop.runtime.provider.ProviderException("PROFILE_NOT_AVAILABLE"));}
            else{
                var profile=providerRegistry.requireProfile(input.context().modelProfileId());
                providerSecret=secretSessions.consume(input.secretSessionId(),profile.providerId());
            }
            if (!activeThreads.add(input.threadId())) {
                if(providerSecret!=null)providerSecret.close();
                return Flux.error(new IllegalStateException("Thread already has an active turn"));
            }
            String message = input.retryOfTurnId()==null?input.lastUserMessage():repository.prepareRetry(input.retryOfTurnId()).message();
            String turnId = input.context().turnId();
            ActiveTurn active = new ActiveTurn(input.threadId());
            active.providerSecret=providerSecret;
            activeTurns.put(turnId, active);
            EventWriter writer = new EventWriter(input, repository);

            try {
                repository.startTurn(new dev.agentdesktop.persistence.ConversationStore.StartTurnCommand(
                        input.threadId(), turnId, input.runId(), input.context().modelProfileId(), Ids.newId("itm"),
                        message, Instant.now(),input.retryOfTurnId()));
            } catch (RuntimeException exception) {
                if(active.providerSecret!=null)active.providerSecret.close();
                cleanup(turnId, active);
                return Flux.error(exception);
            }

            String assistantMessageId = Ids.newId("msg");
            List<SkillResourceContent> loadedResources = java.util.Collections.synchronizedList(new ArrayList<>());
            Flux<AgUiEvent> execution = Flux.defer(() -> {
                SkillTurnContext skillContext = skillAdapter == null
                        ? new SkillTurnContext(turnId,input.context().workspaceId(),0,List.of())
                        : skillAdapter.prepare(turnId,input.context().workspaceId(),input.explicitSkillIds());
                List<AgUiEvent> opening = new ArrayList<>();
                opening.add(writer.event("RUN_STARTED",Map.of("status","running")));
                opening.add(writer.event("TEXT_MESSAGE_START",Map.of("messageId",assistantMessageId,"role","assistant")));
                if(skillAdapter!=null)for(ActivatedSkill skill:skillAdapter.activated(turnId))opening.add(activationEvent(writer,skill));
                Flux<AgUiEvent> body;
                if(harness==null)body=streamProvider(input,active,writer,assistantMessageId,skillContext,loadedResources,0);
                else{
                    var binding=skillAdapter==null?null:skillAdapter.bind(skillContext,skillAdapter.activated(input.context().turnId()));
                    HarnessToolBoundary boundary=harnessBoundary(input,active,skillContext);
                    List<HarnessRunService.ConversationMessage> history=repository.loadConversationHistory(input.threadId(),turnId,MAX_HISTORY_MESSAGES,MAX_HISTORY_CHARACTERS)
                            .stream().map(item->new HarnessRunService.ConversationMessage(item.role(),item.content())).toList();
                    body=harness.stream(new HarnessRunService.RunRequest(input.context().modelProfileId(),active.providerSecret.value(),history,message,binding,boundary))
                            .concatMap(event->mapHarnessEvent(input,writer,assistantMessageId,event));
                }
                return Flux.concat(Flux.fromIterable(opening),body);
            });

            Flux<AgUiEvent> stream = execution
                    .takeUntilOther(active.cancelSignal.asMono())
                    .concatWith(Flux.defer(() -> {
                        if (active.cancelled.get()) {
                            return Flux.just(writer.terminal("CANCELLED", null, "RUN_FINISHED", "STATUS",
                                    Map.of("outcome", "cancelled")));
                        }
                        AgUiEvent end = writer.event("TEXT_MESSAGE_END", Map.of("messageId", assistantMessageId));
                        AgUiEvent finished = writer.terminal("COMPLETED", null, "RUN_FINISHED", "STATUS",
                                Map.of("outcome", "completed"));
                        return Flux.just(end, finished);
                    }))
                    .onErrorResume(exception -> {
                        if (active.cancelled.get()) return Flux.just(writer.terminal("CANCELLED", null, "RUN_FINISHED", "STATUS",
                                Map.of("outcome", "cancelled")));
                        if(exception instanceof PermissionException permission&&Set.of("APPROVAL_EXPIRED","APPROVAL_INVALIDATED","TOOL_APPROVAL_CANCELLED").contains(permission.code()))
                            return Flux.just(writer.terminal("CANCELLED",permission.code(),"RUN_FINISHED","STATUS",Map.of("outcome","cancelled","code",permission.code())));
                        String code=errorCode(exception);
                        logRunFailure(input,exception,code);
                        return Flux.just(writer.terminal("FAILED", code, "RUN_ERROR", "ERROR", Map.of(
                                "code", code, "message", safeMessage(exception))));
                    })
                    .doFinally(signal -> {if(skillAdapter!=null)skillAdapter.closeTurn(turnId);if(active.providerSecret!=null)active.providerSecret.close();cleanup(turnId, active);});
            return stream;
        });
    }

    public int activeTurnCount(){return activeTurns.size();}
    public int activeThreadCount(){return activeThreads.size();}

    private Flux<AgUiEvent> mapHarnessEvent(RunAgentInput input,EventWriter writer,String assistantMessageId,ProviderEvent event){
        if(event instanceof ProviderEvent.TextDelta delta)return Flux.just(writer.event("TEXT_MESSAGE_CONTENT",Map.of("messageId",assistantMessageId,"delta",delta.content())));
        if(event instanceof ProviderEvent.ToolCallStart tool)return Flux.just(writer.event("TOOL_CALL_START",Map.of("toolCallId",tool.toolCallId(),"toolName",tool.toolName())));
        if(event instanceof ProviderEvent.ToolCallArgs tool)return Flux.just(writer.event("TOOL_CALL_ARGS",Map.of("toolCallId",tool.toolCallId(),"delta",tool.delta())));
        if(event instanceof ProviderEvent.ToolCallResult tool){
            Map<String,Object> value=new java.util.LinkedHashMap<>();value.put("toolCallId",tool.toolCallId());value.put("status",tool.status());value.put("resultSummary",tool.resultSummary());value.put("elapsedMs",tool.elapsedMs());if(tool.errorCode()!=null)value.put("errorCode",tool.errorCode());if(!tool.artifacts().isEmpty())value.put("artifacts",tool.artifacts());return Flux.just(writer.event("TOOL_CALL_RESULT",value));
        }
        if(event instanceof ProviderEvent.SkillActivated skill)return Flux.just(writer.event("CUSTOM skill.activated",Map.of(
                "skillId",skill.skillId(),"name",skill.name(),"scope",skill.scope(),"reason",skill.reason(),"contentHash",skill.contentHash())));
        if(event instanceof ProviderEvent.SkillResourceLoaded resource)return Flux.just(writer.auditEvent("CUSTOM skill.resource_loaded",Map.of(
                "skillId",resource.skillId(),"resourceId",resource.resourceId(),"kind",resource.kind(),"byteSize",resource.byteSize(),"contentHash",resource.contentHash())));
        if(event instanceof ProviderEvent.ApprovalRequired approval)return Flux.just(new AgUiEvent("CUSTOM approval.required",input.runId(),input.threadId(),input.context().turnId(),approval.sequence(),Instant.now().toString(),approval.value()));
        if(event instanceof ProviderEvent.ApprovalResolved approval)return Flux.just(new AgUiEvent("CUSTOM approval.resolved",input.runId(),input.threadId(),input.context().turnId(),approval.sequence(),Instant.now().toString(),approval.value()));
        return Flux.empty();
    }

    private HarnessToolBoundary harnessBoundary(RunAgentInput input,ActiveTurn active,SkillTurnContext skillContext){
        SkillHarnessBoundary skillBoundary=skillAdapter==null?null:new SkillHarnessBoundary(skillAdapter,skillContext);
        return new HarnessToolBoundary(){
            @Override public List<HarnessToolBoundary.ToolDefinition> definitions(){
                List<HarnessToolBoundary.ToolDefinition> definitions=new ArrayList<>(toolGateway.harnessDefinitions());
                if(skillBoundary!=null)definitions.addAll(skillBoundary.definitions());
                return List.copyOf(definitions);
            }
            @Override public reactor.core.publisher.Mono<HarnessToolBoundary.ToolExecution> execute(HarnessToolBoundary.ToolCall call,java.util.function.Consumer<ProviderEvent> events){
                if(skillBoundary!=null&&skillBoundary.supports(call.toolId()))return skillBoundary.execute(call,events);
                return reactor.core.publisher.Mono.fromCallable(()->toolGateway.prepare(call.callId(),input.threadId(),input.context().turnId(),call.toolId(),call.arguments()))
                        .subscribeOn(Schedulers.boundedElastic()).flatMap(prepared->{
                            if(active.cancelled.get())return reactor.core.publisher.Mono.error(new PermissionException("PROVIDER_CANCELLED"));
                            PolicyEvaluation evaluation=toolGateway.evaluate(prepared);
                            if(evaluation instanceof Denied denied){if(approvals!=null)approvals.auditPolicyEvaluation(prepared.request(),"HARD_DENY",denied.errorCode());return reactor.core.publisher.Mono.just(failed(call,denied.errorCode()));}
                            if(evaluation instanceof Allowed allowed){if(approvals!=null)approvals.auditPolicyEvaluation(prepared.request(),"AUTO_ALLOW_"+allowed.source().name(),null);return executeHarnessTool(call,prepared,toolGateway.permitAllowed(prepared,allowed.source()),active);}
                            if(approvals==null)return reactor.core.publisher.Mono.just(failed(call,"TOOL_APPROVAL_REQUIRED"));
                            ApprovalCoordinator.Awaiting awaiting=approvals.request(prepared.request());
                            events.accept(new ProviderEvent.ApprovalRequired(awaiting.sequence(),awaiting.requiredValue()));
                            return awaiting.resolution().flatMap(resolution->{
                                events.accept(new ProviderEvent.ApprovalResolved(resolution.sequence()==null?Long.MAX_VALUE:resolution.sequence(),resolution.value()));
                                if(resolution.errorCode()!=null)return reactor.core.publisher.Mono.just(failed(call,resolution.errorCode()));
                                if(resolution.approval().decision()==ApprovalDecision.DENY)return reactor.core.publisher.Mono.just(failed(call,"TOOL_PERMISSION_DENIED"));
                                return executeHarnessTool(call,prepared,toolGateway.permit(resolution.approval()),active);
                            });
                        }).onErrorResume(error->{logToolFailure(call.callId(),call.toolId(),error);return reactor.core.publisher.Mono.just(failed(call,failureCode(error)));});
            }
        };
    }

    private reactor.core.publisher.Mono<HarnessToolBoundary.ToolExecution> executeHarnessTool(HarnessToolBoundary.ToolCall call,ToolGateway.PreparedInvocation prepared,ExecutionPermit permit,ActiveTurn active){
        return reactor.core.publisher.Mono.fromCallable(()->toolGateway.execute(prepared,permit,active.cancelled::get)).subscribeOn(Schedulers.boundedElastic()).map(result->{
            com.fasterxml.jackson.databind.node.ObjectNode modelResult=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            modelResult.set("toolResult",result.modelResult());
            List<String> steering=new ArrayList<>();
            String instruction;
            while((instruction=active.steering.poll())!=null)steering.add(instruction);
            if(!steering.isEmpty())modelResult.set("userSteering",new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(steering));
            return new HarnessToolBoundary.ToolExecution(call.callId(),call.toolId(),result.outcome().name(),modelResult,result.userSummary(),result.errorCode(),result.elapsed().toMillis());
        });
    }
    private static HarnessToolBoundary.ToolExecution failed(HarnessToolBoundary.ToolCall call,String code){return new HarnessToolBoundary.ToolExecution(call.callId(),call.toolId(),"FAILED",com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("error",code),"Tool request was not executed",code,0);}

    private Flux<AgUiEvent> streamProvider(RunAgentInput input,ActiveTurn active,EventWriter writer,String assistantMessageId,
                                           SkillTurnContext skillContext,List<SkillResourceContent> loadedResources,int depth){
        if(depth>10)return Flux.error(new SkillException("SKILL_CONTEXT_BUDGET_EXCEEDED"));
        List<ActivatedSkill> activated=skillAdapter==null?List.of():skillAdapter.activated(input.context().turnId());
        var harnessBinding=skillAdapter==null?null:skillAdapter.bind(skillContext,activated);
        ChatRequest request=new ChatRequest(input.threadId(),input.context().turnId(),input.lastUserMessage(),skillContext.catalog(),activated,List.copyOf(loadedResources),harnessBinding);
        return provider.stream(request).concatMap(providerEvent->{
            if(active.cancelled.get())return Flux.empty();
            if(providerEvent instanceof ProviderEvent.TextDelta delta)return Flux.just(writer.event("TEXT_MESSAGE_CONTENT",Map.of("messageId",assistantMessageId,"delta",delta.content())));
            if(providerEvent instanceof ProviderEvent.ToolCallStart tool)return Flux.just(writer.event("TOOL_CALL_START",Map.of("toolCallId",tool.toolCallId(),"toolName",tool.toolName())));
            if(providerEvent instanceof ProviderEvent.ToolCallArgs tool)return Flux.just(writer.event("TOOL_CALL_ARGS",Map.of("toolCallId",tool.toolCallId(),"delta",tool.delta())));
            if(providerEvent instanceof ProviderEvent.ToolCallResult tool){Map<String,Object> value=new java.util.LinkedHashMap<>();value.put("toolCallId",tool.toolCallId());value.put("status",tool.status());value.put("resultSummary",tool.resultSummary());value.put("elapsedMs",tool.elapsedMs());if(tool.errorCode()!=null)value.put("errorCode",tool.errorCode());if(!tool.artifacts().isEmpty())value.put("artifacts",tool.artifacts());return Flux.just(writer.event("TOOL_CALL_RESULT",value));}
            if(providerEvent instanceof ProviderEvent.StructuredSkillActivate call){
                if(skillAdapter==null)return Flux.error(new SkillException("SKILL_NOT_FOUND"));
                ActivatedSkill skill=skillAdapter.activate(input.context().turnId(),call.skillId());
                return Flux.concat(Flux.just(activationEvent(writer,skill)),streamProvider(input,active,writer,assistantMessageId,skillContext,loadedResources,depth+1));
            }
            if(providerEvent instanceof ProviderEvent.StructuredSkillReadResource call){
                if(skillAdapter==null)return Flux.error(new SkillException("SKILL_RESOURCE_NOT_FOUND"));
                SkillResourceContent resource=skillAdapter.readResource(input.context().turnId(),call.skillId(),call.resourceId());
                if(loadedResources.stream().noneMatch(item->item.resourceId().equals(resource.resourceId())))loadedResources.add(resource);
                AgUiEvent event=writer.auditEvent("CUSTOM skill.resource_loaded",Map.of("skillId",resource.skillId(),"resourceId",resource.resourceId(),"kind",resource.kind(),"byteSize",resource.byteSize(),"contentHash",resource.contentHash()));
                return Flux.concat(Flux.just(event),streamProvider(input,active,writer,assistantMessageId,skillContext,loadedResources,depth+1));
            }
            if(providerEvent instanceof ProviderEvent.StructuredToolCall tool){
                String[] identity=tool.toolId().split(":",3);
                AgUiEvent start=writer.event("TOOL_CALL_START",Map.of("toolCallId",tool.toolCallId(),"toolId",tool.toolId(),"serverId",identity.length>1?identity[1]:"","toolName",identity.length>2?identity[2]:tool.toolId(),"startedAt",Instant.now().toString()));
                Flux<AgUiEvent> invocation=reactor.core.publisher.Mono.fromCallable(()->toolGateway.prepare(tool.toolCallId(),input.threadId(),input.context().turnId(),tool.toolId(),tool.arguments()))
                        .subscribeOn(Schedulers.boundedElastic()).flatMapMany(prepared->invokePrepared(input,active,writer,prepared));
                return Flux.concat(Flux.just(start),invocation).onErrorResume(error->{logToolFailure(tool.toolCallId(),tool.toolId(),error);return Flux.just(toolFailure(writer,tool.toolCallId(),error));});
            }
            return Flux.empty();
        });
    }

    private Flux<AgUiEvent> invokePrepared(RunAgentInput input,ActiveTurn active,EventWriter writer,ToolGateway.PreparedInvocation prepared){
        AgUiEvent args=writer.event("TOOL_CALL_ARGS",Map.of("toolCallId",prepared.invocation().callId(),"argumentsSummary",prepared.request().summary().summary(),"delta",prepared.request().summary().summary()));
        PolicyEvaluation evaluation=toolGateway.evaluate(prepared);
        if(evaluation instanceof Denied denied){
            if(approvals!=null)approvals.auditPolicyEvaluation(prepared.request(),"HARD_DENY",denied.errorCode());
            return Flux.just(args,toolFailure(writer,prepared.invocation().callId(),new PermissionException(denied.errorCode())));
        }
        if(evaluation instanceof Allowed allowed){
            if(approvals!=null)approvals.auditPolicyEvaluation(prepared.request(),"AUTO_ALLOW_"+allowed.source().name(),null);
            return Flux.concat(Flux.just(args),executePrepared(writer,active,prepared,toolGateway.permitAllowed(prepared,allowed.source())));
        }
        if(approvals==null)return Flux.just(args,toolFailure(writer,prepared.invocation().callId(),new PermissionException("TOOL_APPROVAL_REQUIRED")));
        ApprovalCoordinator.Awaiting awaiting=approvals.request(prepared.request());
        AgUiEvent required=new AgUiEvent("CUSTOM approval.required",input.runId(),input.threadId(),input.context().turnId(),awaiting.sequence(),Instant.now().toString(),awaiting.requiredValue());
        Flux<AgUiEvent> continuation=awaiting.resolution().flatMapMany(resolution->{
            AgUiEvent resolved=new AgUiEvent("CUSTOM approval.resolved",input.runId(),input.threadId(),input.context().turnId(),resolution.sequence()==null?Long.MAX_VALUE:resolution.sequence(),Instant.now().toString(),resolution.value());
            if(resolution.errorCode()!=null)return Flux.just(resolved,toolFailure(writer,prepared.invocation().callId(),new PermissionException(resolution.errorCode())));
            if(resolution.approval().decision()==ApprovalDecision.DENY)return Flux.just(resolved,toolFailure(writer,prepared.invocation().callId(),new PermissionException("TOOL_PERMISSION_DENIED")));
            return Flux.concat(Flux.just(resolved),executePrepared(writer,active,prepared,toolGateway.permit(resolution.approval())));
        });
        return Flux.concat(Flux.just(args,required),continuation);
    }

    private Flux<AgUiEvent> executePrepared(EventWriter writer,ActiveTurn active,ToolGateway.PreparedInvocation prepared,ExecutionPermit permit){
        return reactor.core.publisher.Mono.fromCallable(()->toolGateway.execute(prepared,permit,active.cancelled::get)).subscribeOn(Schedulers.boundedElastic()).flatMapMany(result->{
            if(active.cancelled.get())return Flux.empty();Map<String,Object> value=new java.util.LinkedHashMap<>();value.put("toolCallId",prepared.invocation().callId());value.put("status",result.outcome().name());value.put("resultSummary",result.userSummary());value.put("elapsedMs",result.elapsed().toMillis());if(result.errorCode()!=null)value.put("errorCode",result.errorCode());var artifacts=artifacts(result.modelResult());if(!artifacts.isEmpty())value.put("artifacts",artifacts);return Flux.just(writer.event("TOOL_CALL_RESULT",value));});
    }

    private static List<ProviderEvent.OutputArtifact> artifacts(com.fasterxml.jackson.databind.JsonNode modelResult){
        if(modelResult==null||!modelResult.path("artifacts").isArray())return List.of();
        List<ProviderEvent.OutputArtifact> values=new ArrayList<>();modelResult.path("artifacts").forEach(item->{
            String rootId=item.path("rootId").asText("");String relativePath=item.path("relativePath").asText("");String fileName=item.path("fileName").asText("");long byteSize=item.path("byteSize").asLong(-1);
            if("root_default".equals(rootId)&&!relativePath.isBlank()&&!fileName.isBlank()&&byteSize>=0)values.add(new ProviderEvent.OutputArtifact(rootId,relativePath,fileName,item.path("mediaType").asText("application/octet-stream"),byteSize));
        });return List.copyOf(values);
    }

    private static AgUiEvent toolFailure(EventWriter writer,String callId,Throwable error){
        String code=failureCode(error);
        return writer.event("TOOL_CALL_RESULT",Map.of("toolCallId",callId,"status","FAILED","resultSummary","Tool request was not executed","errorCode",code));
    }
    private static String failureCode(Throwable error){return error instanceof PermissionException permission?permission.code():error instanceof SkillException skill?skill.code():"TOOL_EXECUTION_FAILED";}
    private static void logToolFailure(String callId,String toolId,Throwable error){LOGGER.warn("Tool request failed callId={} toolId={} code={} errorType={}",callId,toolId,failureCode(error),error.getClass().getName());}

    private static AgUiEvent activationEvent(EventWriter writer,ActivatedSkill skill){return writer.event("CUSTOM skill.activated",Map.of("skillId",skill.id(),"name",skill.name(),"scope",skill.scope().name(),"reason",skill.reason().name(),"contentHash",skill.contentHash()));}

    public ConversationStore.CancelResult cancel(String turnId) {
        ActiveTurn active = activeTurns.get(turnId);
        if (active != null && active.cancelled.compareAndSet(false, true)) {
            active.cancelSignal.tryEmitEmpty();
        }
        ConversationStore.CancelResult result = repository.requestCancellation(turnId, Instant.now());
        if(approvals!=null)approvals.cancelTurn(turnId,"TOOL_APPROVAL_CANCELLED");
        return result;
    }

    public ConversationStore.AppendResult steer(String turnId,String message){
        ActiveTurn active=activeTurns.get(turnId);
        if(active==null)throw new ConversationRepository.DomainException("TURN_ALREADY_FINISHED");
        ConversationStore.AppendResult result=repository.appendSteering(turnId,Ids.newId("itm"),message,Instant.now());
        active.steering.add(message.strip());
        return result;
    }

    private void cleanup(String turnId, ActiveTurn active) {
        activeTurns.remove(turnId, active);
        activeThreads.remove(active.threadId);
    }

    static String safeMessage(Throwable exception) {
        return switch(errorCode(exception)){
            case "PROVIDER_TIMEOUT" -> "模型响应超时，本轮已安全结束，请重试。";
            case "PROVIDER_UNREACHABLE" -> "暂时无法连接模型服务，本轮已安全结束，请检查网络后重试。";
            case "PROVIDER_PROTOCOL_ERROR" -> "模型服务返回了无法处理的响应，请重试。";
            case "TOOL_CATALOG_TOO_LARGE" -> "可用工具过多，超过本地 Agent 安全上限，请停用部分 MCP 服务后重试。";
            default -> "Agent 运行失败";
        };
    }

    static String errorCode(Throwable exception) {
        for(Throwable current=exception;current!=null;current=current.getCause()){
            if(current instanceof SkillException skill)return skill.code();
            if(current instanceof PermissionException permission)return permission.code();
            if(current instanceof dev.agentdesktop.runtime.provider.ProviderException providerError)return providerError.code();
            if(current.getCause()==current)break;
        }
        return "RUNTIME_ERROR";
    }

    private static void logRunFailure(RunAgentInput input,Throwable exception,String code){
        Throwable root=exception;
        while(root.getCause()!=null&&root.getCause()!=root)root=root.getCause();
        String location=java.util.Arrays.stream(exception.getStackTrace())
                .filter(frame->frame.getClassName().startsWith("dev.agentdesktop."))
                .findFirst().map(StackTraceElement::toString).orElse("unavailable");
        LOGGER.error("Agent run failed threadId={} turnId={} code={} errorType={} rootCauseType={} location={}",
                input.threadId(),input.context().turnId(),code,exception.getClass().getName(),root.getClass().getName(),location);
    }

    private static final class ActiveTurn {
        private final String threadId;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final Sinks.One<Void> cancelSignal = Sinks.one();
        private ProviderSecretSessionStore.ConsumedSecret providerSecret;
        private final java.util.concurrent.ConcurrentLinkedQueue<String> steering=new java.util.concurrent.ConcurrentLinkedQueue<>();
        private ActiveTurn(String threadId) { this.threadId = threadId; }
    }

    private static final class EventWriter {
        private final RunAgentInput input;
        private final ConversationRepository repository;
        private EventWriter(RunAgentInput input, ConversationRepository repository) {
            this.input = input;
            this.repository = repository;
        }

        private AgUiEvent event(String type, Map<String, Object> value) {
            return event(type,"USER",value);
        }

        private AgUiEvent auditEvent(String type,Map<String,Object> value){return event(type,"SYSTEM_AUDIT",value);}

        private AgUiEvent event(String type,String visibility,Map<String, Object> value) {
            String now = Instant.now().toString();
            var appended = repository.appendEvent(new ConversationStore.AppendEventCommand(Ids.newId("itm"),
                    input.threadId(), input.context().turnId(), itemType(type), visibility,
                    Map.of("type", type, "value", value), Instant.parse(now)));
            return new AgUiEvent(type, input.runId(), input.threadId(), input.context().turnId(),
                    appended.sequence(), now, value);
        }

        private AgUiEvent terminal(String status, String errorCode, String type, String itemType, Map<String, Object> value) {
            String now = Instant.now().toString();
            var finalized = repository.finalizeTurn(new ConversationStore.FinalizeTurnCommand(input.context().turnId(), status,
                    errorCode, Ids.newId("itm"), itemType, "USER", Map.of("type", type, "value", value), Instant.parse(now)));
            long sequence = finalized.sequence() == null ? Long.MAX_VALUE : finalized.sequence();
            return new AgUiEvent(type, input.runId(), input.threadId(), input.context().turnId(), sequence, now, value);
        }

        private static String itemType(String eventType) {
            if (eventType.startsWith("TEXT_MESSAGE")) return "ASSISTANT_MESSAGE";
            if (eventType.startsWith("CUSTOM approval.")) return "APPROVAL";
            if ("TOOL_CALL_RESULT".equals(eventType)) return "TOOL_RESULT";
            if (eventType.startsWith("TOOL_CALL")) return "TOOL_CALL";
            if ("RUN_ERROR".equals(eventType)) return "ERROR";
            return "STATUS";
        }
    }
}
