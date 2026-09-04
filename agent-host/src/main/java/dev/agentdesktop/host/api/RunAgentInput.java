package dev.agentdesktop.host.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

public record RunAgentInput(
        @NotBlank String threadId,
        @NotBlank String runId,
        @NotEmpty List<@Valid Message> messages,
        @NotNull @Valid Context context,
        @Size(max=5) List<@Pattern(regexp="^skl_[A-Za-z0-9_-]{8,128}$") String> explicitSkillIds,
        @JsonProperty(access=JsonProperty.Access.WRITE_ONLY)
        @Pattern(regexp="^pss_[A-Za-z0-9_-]{32}$") String secretSessionId,
        @Pattern(regexp="^trn_[A-Za-z0-9_-]{8,128}$") String retryOfTurnId) {

    public RunAgentInput { explicitSkillIds=explicitSkillIds==null?List.of():List.copyOf(explicitSkillIds); }
    public RunAgentInput(String threadId,String runId,List<Message> messages,Context context){this(threadId,runId,messages,context,List.of(),null,null);}
    public RunAgentInput(String threadId,String runId,List<Message> messages,Context context,List<String> explicitSkillIds){this(threadId,runId,messages,context,explicitSkillIds,null,null);}

    public record Message(@NotBlank String id, @NotBlank String role, @NotBlank String content) {}

    public record Context(
            @NotBlank String turnId,
            @NotBlank String workspaceId,
            @NotBlank String modelProfileId,
            @Min(1) @Max(50) int maxIterations) {
        public Context(String turnId,String workspaceId,String modelProfileId){this(turnId,workspaceId,modelProfileId,10);}
    }

    public String lastUserMessage() {
        return messages.stream()
                .filter(message -> "user".equals(message.role()))
                .reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalArgumentException("At least one user message is required"))
                .content();
    }
}
