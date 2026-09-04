package dev.agentdesktop.runtime.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.agentdesktop.runtime.HarnessToolBoundary;
import dev.agentdesktop.runtime.ProviderEvent;
import dev.agentdesktop.runtime.skill.SkillModels.*;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Internal, read-only Harness tools that preserve the application-owned Skill lifecycle. */
public final class SkillHarnessBoundary implements HarnessToolBoundary {
    public static final String ACTIVATE_TOOL_ID="skill.activate";
    public static final String READ_RESOURCE_TOOL_ID="skill.read_resource";
    private final AgentScopeSkillAdapter adapter;
    private final SkillTurnContext context;
    private final List<ToolDefinition> definitions;

    public SkillHarnessBoundary(AgentScopeSkillAdapter adapter,SkillTurnContext context){
        this.adapter=adapter;this.context=context;this.definitions=definitions(context.catalog());
    }

    @Override public List<ToolDefinition> definitions(){return definitions;}
    public boolean supports(String toolId){return ACTIVATE_TOOL_ID.equals(toolId)||READ_RESOURCE_TOOL_ID.equals(toolId);}

    @Override public Mono<ToolExecution> execute(ToolCall call,Consumer<ProviderEvent> events){
        return Mono.fromCallable(()->{
            if(ACTIVATE_TOOL_ID.equals(call.toolId()))return activate(call,events);
            if(READ_RESOURCE_TOOL_ID.equals(call.toolId()))return readResource(call,events);
            throw new SkillException("SKILL_NOT_FOUND");
        }).onErrorResume(error->Mono.just(failed(call,error)));
    }

    private ToolExecution activate(ToolCall call,Consumer<ProviderEvent> events){
        String skillId=requiredText(call.arguments(),"skillId","SKILL_NOT_FOUND");
        ActivatedSkill skill=adapter.activate(context.turnId(),skillId);
        events.accept(new ProviderEvent.SkillActivated(skill.id(),skill.name(),skill.scope().name(),skill.reason().name(),skill.contentHash()));
        ObjectNode result=JsonNodeFactory.instance.objectNode();
        result.put("status","ACTIVATED").put("skillId",skill.id()).put("name",skill.name())
                .put("instructions",skill.instructions());
        var resources=result.putArray("resources");
        for(SkillResourceDescriptor resource:skill.resources())resources.addObject()
                .put("resourceId",resource.id()).put("relativePath",resource.relativePath()).put("kind",resource.kind())
                .put("mediaType",resource.mediaType()).put("byteSize",resource.byteSize())
                .put("executableCandidate",resource.executableCandidate());
        return new ToolExecution(call.callId(),call.toolId(),"SUCCEEDED",result,"Local Skill activated",null,0);
    }

    private ToolExecution readResource(ToolCall call,Consumer<ProviderEvent> events){
        String skillId=requiredText(call.arguments(),"skillId","SKILL_RESOURCE_NOT_FOUND");
        String resourceId=requiredText(call.arguments(),"resourceId","SKILL_RESOURCE_NOT_FOUND");
        SkillResourceContent resource=adapter.readResource(context.turnId(),skillId,resourceId);
        events.accept(new ProviderEvent.SkillResourceLoaded(resource.skillId(),resource.resourceId(),resource.kind(),resource.byteSize(),resource.contentHash()));
        ObjectNode result=JsonNodeFactory.instance.objectNode();
        result.put("status","LOADED").put("skillId",resource.skillId()).put("resourceId",resource.resourceId())
                .put("relativePath",resource.relativePath()).put("kind",resource.kind()).put("content",resource.content());
        return new ToolExecution(call.callId(),call.toolId(),"SUCCEEDED",result,"Local Skill resource loaded",null,0);
    }

    private static String requiredText(JsonNode arguments,String field,String code){
        String value=arguments==null?"":arguments.path(field).asText("").trim();
        if(value.isEmpty())throw new SkillException(code);
        return value;
    }

    private static ToolExecution failed(ToolCall call,Throwable error){
        String code=error instanceof SkillException skill?skill.code():"SKILL_RUNTIME_ERROR";
        return new ToolExecution(call.callId(),call.toolId(),"FAILED",JsonNodeFactory.instance.objectNode().put("error",code),
                "Skill request was not executed",code,0);
    }

    private static List<ToolDefinition> definitions(List<SkillMetadata> catalog){
        if(catalog.isEmpty())return List.of();
        StringBuilder description=new StringBuilder("Activate one local Skill whose public description matches the user's request. "
                +"This loads its SKILL.md through the trusted Runtime and grants no tool authority. Available Skills: ");
        for(SkillMetadata skill:catalog)description.append("\n- ").append(skill.id()).append(" = ").append(skill.name())
                .append(": ").append(skill.description());
        Map<String,Object> activateProperties=new LinkedHashMap<>();
        activateProperties.put("skillId",Map.of("type","string","enum",catalog.stream().map(SkillMetadata::id).toList()));
        Map<String,Object> activateSchema=Map.of("type","object","properties",activateProperties,"required",List.of("skillId"),"additionalProperties",false);
        Map<String,Object> resourceSchema=Map.of("type","object","properties",Map.of(
                "skillId",Map.of("type","string"),"resourceId",Map.of("type","string")),
                "required",List.of("skillId","resourceId"),"additionalProperties",false);
        List<ToolDefinition> result=new ArrayList<>();
        result.add(new ToolDefinition("activate_local_skill",ACTIVATE_TOOL_ID,description.toString(),activateSchema,false));
        result.add(new ToolDefinition("read_local_skill_resource",READ_RESOURCE_TOOL_ID,
                "Read one exact resource returned by activate_local_skill. If SKILL.md requires a reference such as references/default-information.md before execution, load it before calling business or option-provider tools.",resourceSchema,false));
        return List.copyOf(result);
    }
}
