package dev.agentdesktop.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.host.api.AgUiEvent;
import dev.agentdesktop.host.api.RunAgentInput;
import dev.agentdesktop.persistence.ConversationRepository;
import dev.agentdesktop.persistence.LocalDatabase;
import dev.agentdesktop.runtime.MockModelProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TurnCoordinatorTest {
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(60);
    private static final long TEST_TIMEOUT_SECONDS = TEST_TIMEOUT.toSeconds();

    @TempDir Path tempDir;
    private ConversationRepository repository;

    @Test
    void preservesProviderCodeThroughHarnessWrappers() {
        RuntimeException wrapped = new RuntimeException("wrapper",
                new dev.agentdesktop.runtime.provider.ProviderException("PROVIDER_TIMEOUT"));
        assertThat(TurnCoordinator.errorCode(wrapped)).isEqualTo("PROVIDER_TIMEOUT");
        assertThat(TurnCoordinator.safeMessage(wrapped)).isEqualTo("模型响应超时，本轮已安全结束，请重试。");
    }

    @Test
    void presentsProviderIoFailureAsUnreachable() {
        RuntimeException wrapped = new RuntimeException("wrapper",
                new dev.agentdesktop.runtime.provider.ProviderException("PROVIDER_UNREACHABLE"));
        assertThat(TurnCoordinator.errorCode(wrapped)).isEqualTo("PROVIDER_UNREACHABLE");
        assertThat(TurnCoordinator.safeMessage(wrapped)).contains("无法连接模型服务");
    }

    @Test
    void preservesPermissionCodeThroughHarnessWrappers() {
        RuntimeException wrapped = new RuntimeException("wrapper",
                new dev.agentdesktop.runtime.permission.PermissionException("TOOL_CATALOG_TOO_LARGE"));
        assertThat(TurnCoordinator.errorCode(wrapped)).isEqualTo("TOOL_CATALOG_TOO_LARGE");
        assertThat(TurnCoordinator.safeMessage(wrapped)).contains("可用工具过多");
    }

    @BeforeEach
    void setUp() {
        LocalDatabase database = new LocalDatabase(tempDir);
        database.migrate();
        repository = new ConversationRepository(database, new ObjectMapper());
        repository.initializeProfile(tempDir.resolve("workspace"), "tester");
    }

    @Test
    void completesStreamAndPersistsEvents() {
        TurnCoordinator coordinator = new TurnCoordinator(repository, new MockModelProvider(Duration.ZERO));
        List<AgUiEvent> events = coordinator.run(input("thr_complete", "trn_complete", "run_complete"))
                .collectList().block(TEST_TIMEOUT);

        assertThat(events).isNotNull();
        assertThat(events).extracting(AgUiEvent::type)
                .startsWith("RUN_STARTED", "TEXT_MESSAGE_START")
                .endsWith("TEXT_MESSAGE_END", "RUN_FINISHED");
        assertThat(events.getLast().value()).containsEntry("outcome", "completed");
        assertThat(repository.listItems("thr_complete")).isNotEmpty();
    }

    @Test
    void cancellationStopsTextAndFinishesCancelled() throws Exception {
        TurnCoordinator coordinator = new TurnCoordinator(repository, new MockModelProvider(Duration.ofMillis(100)));
        CountDownLatch started = new CountDownLatch(1);
        var future = coordinator.run(input("thr_cancel", "trn_cancel", "run_cancel"))
                .doOnNext(ignored -> started.countDown()).collectList().toFuture();

        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(coordinator.activeTurnCount()).isEqualTo(1);
        assertThat(coordinator.activeThreadCount()).isEqualTo(1);
        long cancellationStartedAt = System.nanoTime();
        coordinator.cancel("trn_cancel");
        coordinator.cancel("trn_cancel");
        List<AgUiEvent> events = future.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(events.getLast().type()).isEqualTo("RUN_FINISHED");
        assertThat(events.getLast().value()).containsEntry("outcome", "cancelled");
        int finalIndex = events.size() - 1;
        assertThat(events.subList(finalIndex + 1, events.size())).isEmpty();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cancellationStartedAt)).isLessThan(10_000);
        assertThat(repository.loadThreadSnapshot("thr_cancel").turns()).singleElement().extracting(turn -> turn.status()).isEqualTo("CANCELLED");
        assertThat(events.stream().filter(event -> event.type().equals("RUN_FINISHED"))).hasSize(1);
        assertThat(coordinator.activeTurnCount()).isZero();
        assertThat(coordinator.activeThreadCount()).isZero();
    }

    @Test
    void toolFixtureUsesNormalEventPersistencePath() {
        TurnCoordinator coordinator = new TurnCoordinator(repository, new MockModelProvider(Duration.ZERO, true));
        RunAgentInput input = new RunAgentInput("thr_tool", "run_tool",
                List.of(new RunAgentInput.Message("msg_user", "user", "__week2_tool_fixture__")),
                new RunAgentInput.Context("trn_tool", "ws_default", "mock"));

        List<AgUiEvent> events = coordinator.run(input).collectList().block(TEST_TIMEOUT);

        assertThat(events).extracting(AgUiEvent::type).containsSubsequence("TOOL_CALL_START", "TOOL_CALL_ARGS", "TOOL_CALL_RESULT");
        assertThat(repository.loadThreadSnapshot("thr_tool").timeline()).anySatisfy(entry -> {
            assertThat(entry.kind()).isEqualTo("TOOL_CALL");
            assertThat(entry.value()).containsEntry("toolName", "fixture.timeline").containsEntry("resultSummary", "fixture result");
        });
    }

    private static RunAgentInput input(String threadId, String turnId, String runId) {
        return new RunAgentInput(threadId, runId,
                List.of(new RunAgentInput.Message("msg_user", "user", "hello")),
                new RunAgentInput.Context(turnId, "ws_default", "mock"));
    }
}
