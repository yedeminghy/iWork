package dev.agentdesktop.host.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentdesktop.host.StartupRecovery;
import dev.agentdesktop.persistence.DatabaseUpgradeCoordinator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public final class ReadyReporter implements ApplicationListener<ApplicationReadyEvent> {
    private final ObjectMapper objectMapper;
    private final ReactiveWebServerApplicationContext context;
    private final DatabaseUpgradeCoordinator upgrades;
    private final StartupRecovery recovery;

    public ReadyReporter(ObjectMapper objectMapper, ReactiveWebServerApplicationContext context,
                         DatabaseUpgradeCoordinator upgrades, StartupRecovery recovery) {
        this.objectMapper = objectMapper;
        this.context = context;
        this.upgrades = upgrades;
        this.recovery = recovery;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        upgrades.markStartupSuccessful();
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        ReadyMessage message = new ReadyMessage("ready", BootstrapConfig.SUPPORTED_PROTOCOL,
                context.getWebServer().getPort(), ProcessHandle.current().pid(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                recovery.interruptedTurns(), recovery.cancelledTurns());
        try {
            System.out.println(objectMapper.writeValueAsString(message));
            System.out.flush();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot emit ready message", exception);
        }
    }

    record ReadyMessage(String type, int protocolVersion, int port, long pid, String nonce,
                        int interruptedTurns, int cancelledTurns) {}
}
