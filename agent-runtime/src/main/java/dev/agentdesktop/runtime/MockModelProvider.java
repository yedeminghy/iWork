package dev.agentdesktop.runtime;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.runtime.skill.SkillModels.ActivatedSkill;
import dev.agentdesktop.runtime.skill.SkillModels.SkillMetadata;
import dev.agentdesktop.runtime.skill.SkillModels.SkillResourceDescriptor;

public final class MockModelProvider implements ModelProvider {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+",Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> STOP_WORDS=Set.of("the","a","an","and","or","to","of","for","when","user","please","only","this","that","with","how","local");
    private final Duration tokenDelay;
    private final boolean toolFixtureEnabled;

    public MockModelProvider(Duration tokenDelay) {
        this(tokenDelay, false);
    }

    public MockModelProvider(Duration tokenDelay, boolean toolFixtureEnabled) {
        this.tokenDelay = Objects.requireNonNull(tokenDelay);
        this.toolFixtureEnabled = toolFixtureEnabled;
    }

    @Override public String id() { return "mock"; }

    @Override
    public Flux<ProviderEvent> stream(ChatRequest request) {
        // The mock models the production decision contract: it can see catalog
        // metadata, never Skill bodies, and returns only structured stable IDs.
        if(toolFixtureEnabled&&(request.message().startsWith("__week3_tool__|")||request.message().startsWith("__week6_tool__|"))){
            String[] fields=request.message().split("\\|",4);
            if(fields.length!=4)return Flux.error(new IllegalArgumentException("Invalid structured tool fixture call"));
            try{return Flux.just(new ProviderEvent.StructuredToolCall("call_"+java.util.UUID.randomUUID(),fields[1],new ObjectMapper().readTree(fields[3])),new ProviderEvent.Completed());}
            catch(Exception exception){return Flux.error(new IllegalArgumentException("Invalid structured tool fixture arguments"));}
        }
        if(request.activatedSkills().isEmpty()){
            SkillMetadata selected=bestMetadataMatch(request.message(),request.skillCatalog());
            if(selected!=null)return Flux.just(new ProviderEvent.StructuredSkillActivate(selected.id()));
        }
        SkillResourceSelection resource=bestResourceMatch(request);
        if(resource!=null)return Flux.just(new ProviderEvent.StructuredSkillReadResource(resource.skillId(),resource.resourceId()));
        if(!request.loadedResources().isEmpty())return text("Resource loaded on demand");
        if(!request.activatedSkills().isEmpty())return text("Skill activated: "+request.activatedSkills().getFirst().name());
        if (toolFixtureEnabled && "__week2_tool_fixture__".equals(request.message())) {
            return Flux.just(
                    new ProviderEvent.ToolCallStart("call_week2_fixture", "fixture.timeline"),
                    new ProviderEvent.ToolCallArgs("call_week2_fixture", "{\"query\":\"example\"}"),
                    new ProviderEvent.ToolCallResult("call_week2_fixture", "SUCCEEDED", "fixture result"),
                    new ProviderEvent.Completed());
        }
        if (toolFixtureEnabled && "__artifact_fixture__".equals(request.message())) {
            return Flux.just(
                    new ProviderEvent.ToolCallStart("call_artifact_fixture", "workspace.run_script"),
                    new ProviderEvent.ToolCallArgs("call_artifact_fixture", "fields: rootId, relativePath"),
                    new ProviderEvent.ToolCallResult("call_artifact_fixture", "SUCCEEDED", "Created preview-fixture.md", null, 12,
                            List.of(new ProviderEvent.OutputArtifact("root_default","preview-fixture.md","preview-fixture.md","text/markdown",44))),
                    new ProviderEvent.TextDelta("测试输出文件已生成。"),new ProviderEvent.Completed());
        }
        if(toolFixtureEnabled&&"__week9_provider_stream_broken__".equals(request.message())){
            return Flux.concat(Flux.just(new ProviderEvent.TextDelta("partial")),
                    Flux.error(new dev.agentdesktop.runtime.provider.ProviderException("PROVIDER_STREAM_BROKEN")));
        }
        return text("Mock response: " + request.message());
    }

    private static SkillMetadata bestMetadataMatch(String message,List<SkillMetadata> catalog){
        Set<String> requestTokens=tokens(message);SkillMetadata winner=null;int best=1;
        for(SkillMetadata item:catalog){
            Set<String> candidate=tokens(item.name()+" "+item.description());
            int score=(int)candidate.stream().filter(requestTokens::contains).count();
            if(score>best){best=score;winner=item;}
        }
        return winner;
    }

    private static SkillResourceSelection bestResourceMatch(ChatRequest request){
        Set<String> requestTokens=tokens(request.message());
        Set<String> loaded=request.loadedResources().stream().map(item->item.resourceId()).collect(java.util.stream.Collectors.toSet());
        SkillResourceSelection winner=null;int best=0;
        for(ActivatedSkill skill:request.activatedSkills())for(SkillResourceDescriptor resource:skill.resources()){
            if(loaded.contains(resource.id())||resource.executableCandidate())continue;
            int score=(int)tokens(resource.relativePath()).stream().filter(requestTokens::contains).count();
            if(score>best){best=score;winner=new SkillResourceSelection(skill.id(),resource.id());}
        }
        return winner;
    }

    private static Set<String> tokens(String value){
        Set<String> result=new HashSet<>();var matcher=WORD.matcher(value.toLowerCase(Locale.ROOT));
        while(matcher.find()){String word=matcher.group();if(word.length()>2&&!STOP_WORDS.contains(word))result.add(word);}
        return result;
    }

    private record SkillResourceSelection(String skillId,String resourceId){}

    private Flux<ProviderEvent> text(String response) {
        Flux<ProviderEvent> text = Flux.range(0, response.length())
                .delayElements(tokenDelay)
                .map(index -> new ProviderEvent.TextDelta(response.substring(index, index + 1)));
        return text.concatWithValues(new ProviderEvent.Completed());
    }
}
