package dev.agentdesktop.host;

import dev.agentdesktop.runtime.provider.ProviderException;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSecretSessionStoreTest {
    @Test
    void consumesProviderBoundSecretExactlyOnce() {
        ProviderSecretSessionStore store = new ProviderSecretSessionStore(new SecureRandom(), Clock.systemUTC(), Duration.ofMinutes(5));
        var session = store.create("deepseek", "sk-test-value-123456789");
        assertThat(store.pendingCount()).isEqualTo(1);

        try (var secret = store.consume(session.secretSessionId(), "deepseek")) {
            assertThat(secret.value()).isEqualTo("sk-test-value-123456789");
        }
        assertThat(store.pendingCount()).isZero();
        assertThatThrownBy(() -> store.consume(session.secretSessionId(), "deepseek"))
                .isInstanceOf(ProviderException.class).hasMessage("PROVIDER_KEY_MISSING");
    }

    @Test
    void rejectsMismatchAndExpiredSecretWithoutReturningIt() {
        Instant now = Instant.parse("2026-08-25T00:00:00Z");
        ProviderSecretSessionStore mismatch = new ProviderSecretSessionStore(new SecureRandom(), Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(5));
        String mismatchId = mismatch.create("qwen", "sk-test-value-123456789").secretSessionId();
        assertThatThrownBy(() -> mismatch.consume(mismatchId, "deepseek")).isInstanceOf(ProviderException.class);

        ProviderSecretSessionStore expired = new ProviderSecretSessionStore(new SecureRandom(), Clock.fixed(now, ZoneOffset.UTC), Duration.ZERO);
        String expiredId = expired.create("qwen", "sk-test-value-123456789").secretSessionId();
        assertThatThrownBy(() -> expired.consume(expiredId, "qwen")).isInstanceOf(ProviderException.class);
        assertThat(expired.pendingCount()).isZero();
    }
}
