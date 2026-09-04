package dev.agentdesktop.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.runtime.HarnessToolBoundary.ToolCall;
import dev.agentdesktop.runtime.HarnessToolBoundary.ToolDefinition;
import dev.agentdesktop.runtime.provider.ProviderRegistry;
import dev.agentdesktop.runtime.skill.AgentScopeSkillAdapter.HarnessTurnBinding;
import io.agentscope.core.agent.Event;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.harness.agent.HarnessAgent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

import dev.agentdesktop.runtime.provider.ProviderException;

/** The sole production ReAct loop. Host coordinates lifecycle but never recursively calls a model. */
public final class HarnessRunService {
    public static final int DEFAULT_MAX_ITERATIONS = 10;
    public static final int MAX_CONFIGURED_ITERATIONS = 50;
    static final Duration DEFAULT_MODEL_IDLE_TIMEOUT = Duration.ofSeconds(60);
    private static final String SYSTEM_PROMPT = "Follow the user's request. Use only the registered application tools. "
            + "Tool descriptions identify the exact MCP server and tool; use the server named by an active Skill and do not call a duplicate merely because its tool name is similar. "
            + "After a successful tool result, continue promptly. If no registered tool can produce a requested file format, explain the limitation and finish. "
            + "Never install runtime dependencies or create package-install scripts; do not run npm install, pip install, or an equivalent installer. "
            + "For PowerPoint, use only the application-managed pptxgenjs runtime. Never fall back to python-pptx; if PPTX_RUNTIME_UNAVAILABLE is returned, report it and stop the PPT attempt. "
            + "Use rootId or workingRootId root_default for workspace tools. Generated files should use a filename directly at the workspace root unless an existing subdirectory is known. "
            + "Use skill.run_script for fixed activated Skill .ps1/.py/.js resources. For a generated .py/.js program, first use workspace.write_text, then pass its returned contentHash to workspace.run_script. "
            + "When the docx Skill is active and a Word report is requested, create a JavaScript report generator using require('docx'), write it to root_default with a root-level .js filename, run it, and report the resulting .docx path. Never reveal hidden reasoning.";
    private final ProviderRegistry providers;
    private final ObjectMapper mapper;
    private final Duration modelIdleTimeout;

    public HarnessRunService(ProviderRegistry providers, ObjectMapper mapper) {
        this(providers, mapper, configuredModelIdleTimeout());
    }

    HarnessRunService(ProviderRegistry providers, ObjectMapper mapper, Duration modelIdleTimeout) {
        this.providers = providers;
        this.mapper = mapper;
        if (modelIdleTimeout == null || modelIdleTimeout.isZero() || modelIdleTimeout.isNegative()) {
            throw new IllegalArgumentException("modelIdleTimeout must be positive");
        }
        this.modelIdleTimeout = modelIdleTimeout;
    }

    public Flux<ProviderEvent> stream(RunRequest request) {
        return Flux.defer(() -> {
            Toolkit toolkit = new Toolkit();
            for (ToolDefinition definition : request.tools().definitions()) toolkit.registerAgentTool(tool(definition, request.tools()));
            HarnessAgent.Builder builder = request.skillBinding() == null ? HarnessAgent.builder() : request.skillBinding().builder();
            Model model = boundedModel(providers.createModel(request.profileId(), request.apiKey()), modelIdleTimeout);
            String systemPrompt=systemPrompt(request.skillBinding());
            HarnessAgent agent = builder.name("agent-desktop").description("Local-first desktop agent")
                    .sysPrompt(systemPrompt)
                    .model(model).toolkit(toolkit).modelExecutionConfig(modelExecutionConfig(modelIdleTimeout))
                    .maxIters(request.maxIterations()).enablePendingToolRecovery(false).enableAgentTracingLog(false)
                    .disableFilesystemTools().disableShellTool().disableMemoryTools().disableMemoryHooks()
                    .disableSessionPersistence().disableWorkspaceContext().disableSubagents().build();
            List<Msg> input = modelMessages(request.history(), request.message());
            Sinks.Many<ProviderEvent> toolEvents = Sinks.many().unicast().onBackpressureBuffer();
            HarnessToolBoundary emitting = emitting(request.tools(), toolEvents);
            Toolkit emittedToolkit = new Toolkit();
            for (ToolDefinition definition : emitting.definitions()) emittedToolkit.registerAgentTool(tool(definition, emitting));

            // Rebuild only when tools exist so every AgentTool writes lifecycle events into this run's sink.
            if (!emitting.definitions().isEmpty()) {
                builder = request.skillBinding() == null ? HarnessAgent.builder() : request.skillBinding().builder();
                agent = builder.name("agent-desktop").description("Local-first desktop agent")
                        .sysPrompt(systemPrompt)
                        .model(model).toolkit(emittedToolkit).modelExecutionConfig(modelExecutionConfig(modelIdleTimeout))
                        .maxIters(request.maxIterations()).enablePendingToolRecovery(false).enableAgentTracingLog(false)
                        .disableFilesystemTools().disableShellTool().disableMemoryTools().disableMemoryHooks()
                        .disableSessionPersistence().disableWorkspaceContext().disableSubagents().build();
            }
            HarnessAgent runningAgent = agent;
            Map<String, String> emittedText = new ConcurrentHashMap<>();
            Flux<ProviderEvent> modelEvents = runningAgent.stream(input, io.agentscope.core.agent.RuntimeContext.empty())
                    .concatMap(event -> visibleText(event, emittedText))
                    .doOnCancel(runningAgent::interrupt)
                    .doFinally(ignored -> toolEvents.tryEmitComplete());
            return Flux.merge(modelEvents, toolEvents.asFlux()).concatWithValues(new ProviderEvent.Completed());
        });
    }

    private HarnessToolBoundary emitting(HarnessToolBoundary delegate, Sinks.Many<ProviderEvent> sink) {
        return new HarnessToolBoundary() {
            @Override public List<ToolDefinition> definitions() { return delegate.definitions(); }
            @Override public Mono<ToolExecution> execute(ToolCall call, java.util.function.Consumer<ProviderEvent> ignored) {
                boolean userVisible=delegate.definitions().stream().filter(item->item.toolId().equals(call.toolId()))
                        .findFirst().map(ToolDefinition::userVisible).orElse(true);
                if(userVisible){
                    sink.tryEmitNext(new ProviderEvent.ToolCallStart(call.callId(), call.toolId()));
                    sink.tryEmitNext(new ProviderEvent.ToolCallArgs(call.callId(), safeArguments(call.arguments())));
                }
                return delegate.execute(call,sink::tryEmitNext).doOnNext(result->{
                    if(userVisible)sink.tryEmitNext(new ProviderEvent.ToolCallResult(result.callId(),result.status(),result.userSummary(),result.errorCode(),result.elapsedMs(),artifacts(result.modelResult())));
                });
            }
        };
    }

    static String systemPrompt(HarnessTurnBinding binding){
        if(binding==null||binding.contextPrompt()==null||binding.contextPrompt().isBlank())return SYSTEM_PROMPT;
        return SYSTEM_PROMPT+"\n\n"+binding.contextPrompt();
    }

    private AgentTool tool(ToolDefinition definition, HarnessToolBoundary boundary) {
        return new AgentTool() {
            @Override public String getName() { return definition.agentName(); }
            @Override public String getDescription() { return definition.description(); }
            @Override public Map<String, Object> getParameters() { return definition.parameters(); }
            @Override public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                String callId = param.getToolUseBlock().getId();
                return boundary.execute(new ToolCall(callId, definition.agentName(), definition.toolId(), mapper.valueToTree(param.getInput())), ignored -> {})
                        .map(result -> ToolResultBlock.of(callId, definition.agentName(),
                                TextBlock.builder().text(modelText(result)).build()));
            }
        };
    }

    private Flux<ProviderEvent> visibleText(Event event, Map<String, String> emitted) {
        Msg message = event.getMessage();
        if (message == null || message.getRole() != MsgRole.ASSISTANT) return Flux.empty();
        // ThinkingBlock and every non-text provider/debug block are intentionally unreachable here.
        String value = message.getContentBlocks(TextBlock.class).stream().map(TextBlock::getText).reduce("", String::concat);
        if (value.isEmpty()) return Flux.empty();
        String id = message.getId() == null ? "assistant" : message.getId();
        String previous = emitted.getOrDefault(id, "");
        String delta = value.startsWith(previous) ? value.substring(previous.length()) : value;
        emitted.put(id, value.startsWith(previous) ? value : previous + value);
        return delta.isEmpty() ? Flux.empty() : Flux.just(new ProviderEvent.TextDelta(delta));
    }

    private String modelText(HarnessToolBoundary.ToolExecution result) {
        if (result.modelResult() == null) return "{\"status\":\"" + result.status() + "\"}";
        String value = result.modelResult().toString();
        return value.length() <= 65_536 ? value : value.substring(0, 65_536);
    }

    private static List<ProviderEvent.OutputArtifact> artifacts(com.fasterxml.jackson.databind.JsonNode modelResult){
        if(modelResult==null)return List.of();
        com.fasterxml.jackson.databind.JsonNode values=modelResult.path("artifacts");
        if(!values.isArray())values=modelResult.path("toolResult").path("artifacts");
        if(!values.isArray())return List.of();
        java.util.ArrayList<ProviderEvent.OutputArtifact> result=new java.util.ArrayList<>();
        values.forEach(item->{
            String rootId=item.path("rootId").asText("");String relativePath=item.path("relativePath").asText("");
            String fileName=item.path("fileName").asText("");String mediaType=item.path("mediaType").asText("application/octet-stream");
            long byteSize=item.path("byteSize").asLong(-1);
            if("root_default".equals(rootId)&&!relativePath.isBlank()&&!fileName.isBlank()&&byteSize>=0)
                result.add(new ProviderEvent.OutputArtifact(rootId,relativePath,fileName,mediaType,byteSize));
        });
        return List.copyOf(result);
    }

    static Model boundedModel(Model delegate, Duration idleTimeout) {
        return new Model() {
            @Override public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return delegate.stream(messages, tools, options)
                        .timeout(idleTimeout)
                        .onErrorMap(TimeoutException.class, error -> new ProviderException("PROVIDER_TIMEOUT", error))
                        .onErrorMap(error -> !(error instanceof ProviderException), HarnessRunService::providerFailure);
            }

            @Override public String getModelName() { return delegate.getModelName(); }
        };
    }

    private static ProviderException providerFailure(Throwable error) {
        for(Throwable current=error;current!=null;current=current.getCause()){
            if(current instanceof SocketTimeoutException || current instanceof TimeoutException)
                return new ProviderException("PROVIDER_TIMEOUT",error);
            if(current instanceof ConnectException || current instanceof UnknownHostException || current instanceof IOException)
                return new ProviderException("PROVIDER_UNREACHABLE",error);
            if(current.getCause()==current)break;
        }
        return new ProviderException("PROVIDER_PROTOCOL_ERROR",error);
    }

    static ExecutionConfig modelExecutionConfig(Duration timeout) {
        return ExecutionConfig.builder().timeout(timeout).maxAttempts(1).build();
    }

    private static Duration configuredModelIdleTimeout() {
        long configured = Long.getLong("agentdesktop.provider-idle-timeout-seconds", DEFAULT_MODEL_IDLE_TIMEOUT.toSeconds());
        return Duration.ofSeconds(Math.max(15, Math.min(configured, 300)));
    }

    private static String safeArguments(com.fasterxml.jackson.databind.JsonNode arguments) {
        if (arguments == null || !arguments.isObject()) return "{}";
        java.util.List<String> keys = new java.util.ArrayList<>();
        arguments.fieldNames().forEachRemaining(key -> {
            if (keys.size() < 8) keys.add(key.matches("(?i).*(secret|token|password|authorization|cookie|content|body).*") ? "[sensitive]" : key);
        });
        return "fields: " + String.join(", ", keys);
    }

    static List<Msg> modelMessages(List<ConversationMessage> history,String currentMessage) {
        java.util.ArrayList<Msg> messages=new java.util.ArrayList<>();
        if(history!=null)for(ConversationMessage item:history){
            if(item==null||item.content()==null||item.content().isBlank())continue;
            MsgRole role="assistant".equals(item.role())?MsgRole.ASSISTANT:MsgRole.USER;
            messages.add(Msg.builder().name(item.role()).role(role).textContent(item.content()).build());
        }
        if(currentMessage==null||currentMessage.isBlank())throw new IllegalArgumentException("message is required");
        messages.add(Msg.builder().name("user").role(MsgRole.USER).textContent(currentMessage).build());
        return List.copyOf(messages);
    }

    public record ConversationMessage(String role,String content) {
        public ConversationMessage {
            if(!List.of("user","assistant").contains(role))throw new IllegalArgumentException("Unsupported conversation role");
            if(content==null||content.isBlank())throw new IllegalArgumentException("Conversation content is required");
        }
    }
    public record RunRequest(String profileId,String apiKey,List<ConversationMessage> history,String message,
                             HarnessTurnBinding skillBinding,HarnessToolBoundary tools,int maxIterations) {
        public RunRequest {
            history=history==null?List.of():List.copyOf(history);
            if(maxIterations<1||maxIterations>MAX_CONFIGURED_ITERATIONS)throw new IllegalArgumentException("Invalid maxIterations");
        }
        public RunRequest(String profileId,String apiKey,List<ConversationMessage> history,String message,
                          HarnessTurnBinding skillBinding,HarnessToolBoundary tools){
            this(profileId,apiKey,history,message,skillBinding,tools,DEFAULT_MAX_ITERATIONS);
        }
    }
}
