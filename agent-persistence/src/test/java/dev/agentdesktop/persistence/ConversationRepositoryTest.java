package dev.agentdesktop.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationRepositoryTest {
    @TempDir Path tempDir;
    private ConversationRepository repository;
    private LocalDatabase database;

    @BeforeEach
    void setUp() {
        database = new LocalDatabase(tempDir);
        database.migrate();
        repository = new ConversationRepository(database, new ObjectMapper());
        repository.initializeProfile(tempDir.resolve("workspace"), "tester");
    }

    @Test
    void persistsConversationTimeline() {
        repository.ensureThread("thr_test", "mock", "hello");
        repository.createTurn("trn_test", "thr_test", "run_test", "mock");
        repository.appendItem("itm_test", "thr_test", "trn_test", 1,
                "USER_MESSAGE", "USER", Map.of("content", "hello"));
        repository.finishTurn("trn_test", "COMPLETED", null);

        assertThat(repository.listItems("thr_test")).singleElement().satisfies(item -> {
            assertThat(item.type()).isEqualTo("USER_MESSAGE");
            assertThat(item.payloadJson()).contains("hello");
        });
    }

    @Test
    void startTurnIsAtomicAndEventReplayIsIdempotent() {
        var command = new ConversationStore.StartTurnCommand("thr_atomic", "trn_atomic", "run_atomic", "mock", "itm_user", "hello", Instant.now());
        repository.startTurn(command);
        assertThatThrownBy(() -> repository.startTurn(new ConversationStore.StartTurnCommand("thr_atomic", "trn_other", "run_other", "mock", "itm_other", "second", Instant.now())))
                .isInstanceOf(ConversationRepository.DomainException.class).hasMessage("TURN_CONFLICT");
        var event = new ConversationStore.AppendEventCommand("itm_event", "thr_atomic", "trn_atomic", "ASSISTANT_MESSAGE", "USER",
                Map.of("type", "TEXT_MESSAGE_START", "value", Map.of("messageId", "msg_agent", "role", "assistant")), Instant.now());
        assertThat(repository.appendEvent(event).replayed()).isFalse();
        assertThat(repository.appendEvent(event).replayed()).isTrue();
        assertThatThrownBy(() -> repository.appendEvent(new ConversationStore.AppendEventCommand("itm_event", "thr_atomic", "trn_atomic", "ASSISTANT_MESSAGE", "USER",
                Map.of("type", "TEXT_MESSAGE_START", "value", Map.of("messageId", "different")), Instant.now())))
                .isInstanceOf(ConversationRepository.DomainException.class).hasMessage("EVENT_CONFLICT");
        assertThat(repository.listItems("thr_atomic")).hasSize(2);
    }

    @Test
    void projectsToolTimelineAndHidesSystemAudit() {
        repository.startTurn(new ConversationStore.StartTurnCommand("thr_tools", "trn_tools", "run_tools", "mock", "itm_user", "show tool", Instant.now()));
        repository.appendEvent(event("itm_call", "TOOL_CALL", "TOOL_CALL_START", Map.of("toolCallId", "call_1", "toolName", "fixture.tool")));
        repository.appendEvent(event("itm_args", "TOOL_CALL", "TOOL_CALL_ARGS", Map.of("toolCallId", "call_1", "delta", "{\"query\":\"example\"}")));
        repository.appendEvent(event("itm_result", "TOOL_RESULT", "TOOL_CALL_RESULT", Map.of("toolCallId", "call_1", "status", "SUCCEEDED", "resultSummary", "fixture result")));
        repository.appendItem("itm_audit", "thr_tools", "trn_tools", 4, "STATUS", "SYSTEM_AUDIT", Map.of("token", "never exposed"));
        ThreadSnapshot snapshot = repository.loadThreadSnapshot("thr_tools");
        assertThat(snapshot.timeline()).anySatisfy(entry -> {
            assertThat(entry.kind()).isEqualTo("TOOL_CALL");
            assertThat(entry.value()).containsEntry("toolName", "fixture.tool").containsEntry("resultSummary", "fixture result");
        });
        assertThat(snapshot.timeline()).noneMatch(entry -> entry.id().equals("itm_audit"));
    }

    @Test
    void reconcilesOnceAndpreventsArchivingActiveThread() {
        repository.startTurn(new ConversationStore.StartTurnCommand("thr_recovery", "trn_recovery", "run_recovery", "mock", "itm_user", "recover", Instant.now()));
        assertThatThrownBy(() -> repository.setThreadArchived("thr_recovery", true)).isInstanceOf(ConversationRepository.DomainException.class).hasMessage("THREAD_BUSY");
        assertThat(repository.reconcileUnfinishedTurns(Instant.now()).interrupted()).isEqualTo(1);
        assertThat(repository.reconcileUnfinishedTurns(Instant.now()).interrupted()).isZero();
        assertThat(repository.loadThreadSnapshot("thr_recovery").turns()).singleElement().extracting(TurnSummary::status).isEqualTo("INTERRUPTED");
        repository.setThreadArchived("thr_recovery", true);
        assertThat(repository.listThreads(new ConversationStore.ThreadQuery("ACTIVE", 50, null)).items()).noneMatch(thread -> thread.id().equals("thr_recovery"));
        assertThat(repository.setThreadArchived("thr_recovery", false).status()).isEqualTo("ACTIVE");
    }

    @Test
    void boundsSteeringAndRetriesOnlyTerminalTurnsWithOriginalMessage() {
        repository.startTurn(new ConversationStore.StartTurnCommand("thr_retry", "trn_original", "run_original", "deepseek-v4-pro", "itm_original", "original prompt", Instant.now()));
        for (int index = 0; index < 5; index++) {
            repository.appendSteering("trn_original", "itm_steer_" + index, "instruction " + index, Instant.now());
        }
        assertThatThrownBy(() -> repository.appendSteering("trn_original", "itm_steer_6", "too many", Instant.now()))
                .isInstanceOf(ConversationRepository.DomainException.class).hasMessage("STEERING_LIMIT_EXCEEDED");
        assertThatThrownBy(() -> repository.appendSteering("trn_original", "itm_large", "x".repeat(4097), Instant.now()))
                .isInstanceOf(ConversationRepository.DomainException.class).hasMessage("STEERING_TOO_LARGE");
        assertThatThrownBy(() -> repository.prepareRetry("trn_original"))
                .isInstanceOf(ConversationRepository.DomainException.class).hasMessage("TURN_NOT_RETRYABLE");

        repository.finalizeTurn(new ConversationStore.FinalizeTurnCommand("trn_original", "FAILED", "PROVIDER_TIMEOUT",
                "itm_failure", "ERROR", "USER", Map.of("type", "RUN_ERROR", "value", Map.of("code", "PROVIDER_TIMEOUT")), Instant.now()));
        assertThatThrownBy(() -> repository.appendSteering("trn_original", "itm_late", "late", Instant.now()))
                .isInstanceOf(ConversationRepository.DomainException.class).hasMessage("TURN_ALREADY_FINISHED");
        var retry = repository.prepareRetry("trn_original");
        assertThat(retry.message()).isEqualTo("original prompt");
        assertThat(retry.modelProfileId()).isEqualTo("deepseek-v4-pro");

        repository.startTurn(new ConversationStore.StartTurnCommand("thr_retry", "trn_retry", "run_retry", "deepseek-v4-pro",
                "itm_retry", "must not replace original", Instant.now(), "trn_original"));
        var retryTurn = repository.loadThreadSnapshot("thr_retry").turns().getLast();
        assertThat(retryTurn.retryOfTurnId()).isEqualTo("trn_original");
        assertThat(repository.listItems("thr_retry").getLast().payloadJson()).contains("original prompt").contains("RETRY");
    }

    @Test
    void exposesOnlyAggregateDatabaseDiagnostics() {
        repository.startTurn(new ConversationStore.StartTurnCommand("thr_diag", "trn_diag", "run_diag", "mock", "itm_diag", "sensitive sentinel", Instant.now()));
        var diagnostic=database.diagnosticStats();
        assertThat(diagnostic.integrity()).isEqualTo("ok");
        assertThat(diagnostic.schemaVersion()).isEqualTo(8);
        assertThat(diagnostic.threadCount()).isEqualTo(1);
        assertThat(diagnostic.turnCount()).isEqualTo(1);
        assertThat(diagnostic.itemCount()).isEqualTo(1);
        assertThat(diagnostic.toString()).doesNotContain("sensitive sentinel");
    }

    @Test
    void threadPreviewUsesLatestUserMessageInsteadOfStreamingEventShells() {
        repository.startTurn(new ConversationStore.StartTurnCommand("thr_preview", "trn_preview", "run_preview", "mock", "itm_preview_user", "用户问题", Instant.now()));
        repository.appendItem("itm_preview_assistant", "thr_preview", "trn_preview", 1, "ASSISTANT_MESSAGE", "USER", Map.of("content", "  已生成报告。\n文件位于工作区。  "));
        repository.appendItem("itm_preview_status", "thr_preview", "trn_preview", 2, "STATUS", "USER", Map.of("status", "COMPLETED"));

        var thread=repository.listThreads(new ConversationStore.ThreadQuery("ACTIVE",50,null)).items().stream().filter(item->item.id().equals("thr_preview")).findFirst().orElseThrow();

        assertThat(thread.preview()).isEqualTo("用户问题");
        assertThat(repository.loadThreadSnapshot("thr_preview").thread().preview()).isEqualTo("用户问题");
    }

    @Test
    void reconstructsCompletedConversationHistoryAndExcludesCurrentTurn() {
        repository.startTurn(new ConversationStore.StartTurnCommand("thr_history","trn_first","run_first","mock","itm_first_user","查询夏普比率算法说明",Instant.now()));
        repository.appendEvent(new ConversationStore.AppendEventCommand("itm_first_start","thr_history","trn_first","ASSISTANT_MESSAGE","USER",
                Map.of("type","TEXT_MESSAGE_START","value",Map.of("messageId","msg_first","role","assistant")),Instant.now()));
        repository.appendEvent(new ConversationStore.AppendEventCommand("itm_first_content_1","thr_history","trn_first","ASSISTANT_MESSAGE","USER",
                Map.of("type","TEXT_MESSAGE_CONTENT","value",Map.of("messageId","msg_first","delta","夏普比率等于")),Instant.now()));
        repository.appendEvent(new ConversationStore.AppendEventCommand("itm_first_content_2","thr_history","trn_first","ASSISTANT_MESSAGE","USER",
                Map.of("type","TEXT_MESSAGE_CONTENT","value",Map.of("messageId","msg_first","delta","超额收益除以收益波动率")),Instant.now()));
        repository.appendEvent(new ConversationStore.AppendEventCommand("itm_first_end","thr_history","trn_first","ASSISTANT_MESSAGE","USER",
                Map.of("type","TEXT_MESSAGE_END","value",Map.of("messageId","msg_first")),Instant.now()));
        repository.finalizeTurn(new ConversationStore.FinalizeTurnCommand("trn_first","COMPLETED",null,"itm_first_finished","STATUS","USER",
                Map.of("type","RUN_FINISHED","value",Map.of("outcome","completed")),Instant.now()));
        repository.startTurn(new ConversationStore.StartTurnCommand("thr_history","trn_second","run_second","mock","itm_second_user","上面算法说明，生成Word报告",Instant.now()));

        assertThat(repository.loadConversationHistory("thr_history","trn_second",40,64_000)).containsExactly(
                new ConversationStore.ConversationMessage("user","查询夏普比率算法说明"),
                new ConversationStore.ConversationMessage("assistant","夏普比率等于超额收益除以收益波动率"));
    }

    private static ConversationStore.AppendEventCommand event(String id, String itemType, String type, Map<String, Object> value) {
        return new ConversationStore.AppendEventCommand(id, "thr_tools", "trn_tools", itemType, "USER", Map.of("type", type, "value", value), Instant.now());
    }
}
