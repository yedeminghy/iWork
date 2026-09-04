package dev.agentdesktop.host;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.persistence.ConversationRepository;
import dev.agentdesktop.persistence.ConversationStore;
import dev.agentdesktop.persistence.LocalDatabase;
import dev.agentdesktop.persistence.PermissionRepository;
import dev.agentdesktop.runtime.permission.PermissionModels.CapabilityClass;
import dev.agentdesktop.runtime.permission.PermissionModels.PermissionRequest;
import dev.agentdesktop.runtime.permission.PermissionModels.PermissionSubject;
import dev.agentdesktop.runtime.permission.PermissionModels.ResourceScope;
import dev.agentdesktop.runtime.permission.PermissionModels.SafeInvocationSummary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApprovalCoordinatorTest {
    @Test
    void approvalExpiryPrecedesToolTimeoutAndReturnsFailedToolResolution(@TempDir Path home) {
        LocalDatabase database = new LocalDatabase(home);
        database.migrate();
        ObjectMapper mapper = new ObjectMapper();
        ConversationRepository conversations = new ConversationRepository(database, mapper);
        conversations.initializeProfile(home.resolve("workspace"), "test");
        Instant now = Instant.parse("2026-08-31T05:00:00Z");
        conversations.startTurn(new ConversationStore.StartTurnCommand(
                "thr_approval", "trn_approval", "run_approval", "mock", "itm_user", "hello", now));
        ApprovalCoordinator coordinator = new ApprovalCoordinator(
                new PermissionRepository(database, mapper), Clock.fixed(now, ZoneOffset.UTC));

        assertThat(ApprovalCoordinator.APPROVAL_TTL).isLessThan(Duration.ofMinutes(5));
        StepVerifier.withVirtualTime(() -> coordinator.request(request(now)).resolution())
                .thenAwait(ApprovalCoordinator.APPROVAL_TTL.plusMillis(1))
                .assertNext(resolution -> {
                    assertThat(resolution.errorCode()).isEqualTo("APPROVAL_EXPIRED");
                    assertThat(resolution.approval()).isNull();
                    assertThat(resolution.value()).containsEntry("decision", "EXPIRED");
                })
                .verifyComplete();

        assertThat(coordinator.pendingCount()).isZero();
        assertThat(conversations.loadThreadSnapshot("thr_approval").turns())
                .singleElement().extracting(turn -> turn.status()).isEqualTo("RUNNING");
    }

    private static PermissionRequest request(Instant now) {
        PermissionSubject subject = new PermissionSubject(
                "WORKSPACE_FILE", "workspace.write_text", "write file", "root_default", "source");
        ResourceScope resource = new ResourceScope(
                "root_default", "report.pptx.js", null, null, null, null, "file");
        SafeInvocationSummary summary = new SafeInvocationSummary(
                "high", "写入 PPT 生成脚本", "将生成脚本写入工作区", Map.of("kind", "workspace"),
                Map.of("path", "report.pptx.js"), "写入前需要批准");
        return new PermissionRequest("call_write", "thr_approval", "trn_approval", CapabilityClass.WRITE,
                subject, resource, "sha256:args", "sha256:invocation", "sha256:grant", summary, null, now);
    }
}
