package dev.agentdesktop.runtime.skill;

import dev.agentdesktop.runtime.skill.SkillModels.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class DefaultSkillRuntimeBridge implements SkillRuntimeBridge {
    private static final int MAX_ACTIVATED = 5;
    private static final int MAX_INSTRUCTION_TOKENS = 20_000;
    private static final long MAX_RESOURCE_BYTES_PER_TURN = 1024L * 1024L;
    private static final String PPTX_RUNTIME_POLICY = """

            Application-managed PPT runtime policy (overrides package-install or Python fallback advice above):
            - Generate PowerPoint files with JavaScript and require('pptxgenjs'); the application injects the managed module path.
            - Never run npm install, pip install, or create an installation script during a turn.
            - Never fall back to python-pptx. If the managed require fails, return PPTX_RUNTIME_UNAVAILABLE.
            """;

    private final LocalSkillRegistry registry;
    private final Predicate<String> runtimeCapabilityAvailable;
    private final Map<String, TurnState> turns = new ConcurrentHashMap<>();

    public DefaultSkillRuntimeBridge(LocalSkillRegistry registry) { this(registry, ignored -> true); }
    public DefaultSkillRuntimeBridge(LocalSkillRegistry registry, Predicate<String> runtimeCapabilityAvailable) {
        this.registry = Objects.requireNonNull(registry);
        this.runtimeCapabilityAvailable = Objects.requireNonNull(runtimeCapabilityAvailable);
    }

    @Override
    public SkillTurnContext prepare(String turnId, String workspaceId, List<String> explicitSkillIds) {
        if (turnId == null || !turnId.matches("trn_[A-Za-z0-9_-]{8,128}")) throw new IllegalArgumentException("Invalid turn id");
        List<String> explicit = explicitSkillIds == null ? List.of() : List.copyOf(explicitSkillIds);
        if (explicit.size() > MAX_ACTIVATED || new HashSet<>(explicit).size() != explicit.size()) throw new SkillException("SKILL_CONTEXT_BUDGET_EXCEEDED");
        SkillCatalogSnapshot snapshot = registry.snapshot(workspaceId);
        TurnState state = new TurnState(workspaceId, snapshot.generation(), snapshot.skills());
        if (turns.putIfAbsent(turnId,state)!=null) throw new IllegalStateException("Skill turn already prepared");
        try {
            for(String skillId:explicit) activate(turnId,skillId,ActivationReason.EXPLICIT);
            return new SkillTurnContext(turnId,workspaceId,snapshot.generation(),snapshot.skills());
        } catch (RuntimeException exception) { turns.remove(turnId,state); throw exception; }
    }

    @Override
    public ActivatedSkill activate(String turnId, String skillId, ActivationReason reason) {
        TurnState state = requireTurn(turnId);
        synchronized (state) {
            ActivatedSkill existing=state.activated.get(skillId); if(existing!=null)return existing;
            if(!state.catalog.containsKey(skillId))throw new SkillException("SKILL_NOT_FOUND");
            if(state.activated.size()>=MAX_ACTIVATED)throw new SkillException("SKILL_CONTEXT_BUDGET_EXCEEDED");
            SkillCatalogStore.Entry row=registry.runtimeEntry(skillId);
            if (requiresPptxRuntime(row) && !runtimeCapabilityAvailable.test("pptx")) throw new SkillException("PPTX_RUNTIME_UNAVAILABLE");
            String instructions;
            try { instructions=registry.loadInstructions(row); }
            catch(SkillException exception){if("SKILL_STALE".equals(exception.code())){try{registry.refresh(row.scope(),state.workspaceId);}catch(RuntimeException ignored){}}throw exception;}
            if(requiresPptxRuntime(row))instructions+=PPTX_RUNTIME_POLICY;
            int tokens=Math.max(1,instructions.codePointCount(0,instructions.length())/4);
            if(state.instructionTokens+tokens>MAX_INSTRUCTION_TOKENS)throw new SkillException("SKILL_CONTEXT_BUDGET_EXCEEDED");
            ActivatedSkill activated=new ActivatedSkill(row.id(),row.name(),row.scope(),instructions,row.contentHash(),registry.listResources(row.id()),reason);
            registry.recordActivation(turnId,row,reason);
            state.instructionTokens+=tokens; state.activated.put(skillId,activated);
            return activated;
        }
    }

    @Override
    public SkillResourceContent readResource(String turnId, String skillId, String resourceId) {
        TurnState state=requireTurn(turnId);
        synchronized(state){
            ActivatedSkill activated=state.activated.get(skillId);if(activated==null)throw new SkillException("SKILL_DISABLED");
            SkillResourceContent existing=state.resources.get(resourceId);if(existing!=null)return existing;
            SkillResourceDescriptor descriptor=activated.resources().stream().filter(item->item.id().equals(resourceId)).findFirst().orElseThrow(()->new SkillException("SKILL_RESOURCE_NOT_FOUND"));
            if(state.resourceBytes+descriptor.byteSize()>MAX_RESOURCE_BYTES_PER_TURN)throw new SkillException("SKILL_CONTEXT_BUDGET_EXCEEDED");
            SkillCatalogStore.Entry row=registry.runtimeEntry(skillId);
            SkillResourceContent content=registry.readRuntimeResource(row,descriptor);
            state.resourceBytes+=content.byteSize();state.resources.put(resourceId,content);return content;
        }
    }

    @Override public List<ActivatedSkill> activated(String turnId){TurnState state=requireTurn(turnId);synchronized(state){return List.copyOf(state.activated.values());}}
    @Override public SkillScriptPlan resolveScript(String turnId,String skillId,String resourceId){TurnState state=requireTurn(turnId);synchronized(state){ActivatedSkill activated=state.activated.get(skillId);if(activated==null)throw new SkillException("SCRIPT_NOT_ACTIVATED");SkillResourceDescriptor descriptor=activated.resources().stream().filter(item->item.id().equals(resourceId)).findFirst().orElseThrow(()->new SkillException("SKILL_RESOURCE_NOT_FOUND"));return registry.resolveRuntimeScript(registry.runtimeEntry(skillId),descriptor);}}
    @Override public void closeTurn(String turnId){turns.remove(turnId);}
    private TurnState requireTurn(String id){TurnState state=turns.get(id);if(state==null)throw new SkillException("SKILL_NOT_FOUND");return state;}
    private static boolean requiresPptxRuntime(SkillCatalogStore.Entry row){
        String declared=row.metadata().get("runtime");
        return row.name().equalsIgnoreCase("pptx") || (declared!=null && Arrays.stream(declared.split(",")).map(String::trim).anyMatch(value->value.equalsIgnoreCase("pptx")));
    }

    private static final class TurnState {
        private final String workspaceId; private final long generation;
        private final Map<String,SkillMetadata> catalog; private final Map<String,ActivatedSkill> activated=new LinkedHashMap<>();
        private final Map<String,SkillResourceContent> resources=new LinkedHashMap<>(); private int instructionTokens; private long resourceBytes;
        private TurnState(String workspaceId,long generation,List<SkillMetadata> catalog){this.workspaceId=workspaceId;this.generation=generation;Map<String,SkillMetadata> values=new LinkedHashMap<>();catalog.forEach(item->values.put(item.id(),item));this.catalog=Map.copyOf(values);}
    }
}
