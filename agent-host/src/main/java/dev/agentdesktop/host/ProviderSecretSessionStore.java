package dev.agentdesktop.host;

import dev.agentdesktop.runtime.provider.ProviderException;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory, provider-bound, one-use secret handoff from Electron Main to Host. */
public final class ProviderSecretSessionStore implements AutoCloseable {
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(5);
    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random;
    private final Clock clock;
    private final Duration ttl;

    public ProviderSecretSessionStore() { this(new SecureRandom(), Clock.systemUTC(), DEFAULT_TTL); }

    ProviderSecretSessionStore(SecureRandom random, Clock clock, Duration ttl) {
        this.random = random;
        this.clock = clock;
        this.ttl = ttl;
    }

    public SecretSession create(String providerId, String apiKey) {
        purgeExpired();
        if (!"deepseek".equals(providerId) && !"qwen".equals(providerId)) throw new ProviderException("PROFILE_NOT_AVAILABLE");
        if (apiKey == null || apiKey.isBlank() || apiKey.length() > 16_384 || apiKey.indexOf('\r') >= 0 || apiKey.indexOf('\n') >= 0) {
            throw new ProviderException("PROVIDER_KEY_MISSING");
        }
        byte[] value = new byte[24];
        random.nextBytes(value);
        String id = "pss_" + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        Instant expiresAt = clock.instant().plus(ttl);
        sessions.put(id, new Entry(providerId, apiKey.toCharArray(), expiresAt));
        return new SecretSession(id, expiresAt);
    }

    public ConsumedSecret consume(String sessionId, String providerId) {
        purgeExpired();
        if (sessionId == null || !sessionId.matches("^pss_[A-Za-z0-9_-]{32}$")) throw new ProviderException("PROVIDER_KEY_MISSING");
        Entry entry = sessions.remove(sessionId);
        if (entry == null) throw new ProviderException("PROVIDER_KEY_MISSING");
        if (!entry.providerId.equals(providerId)) {
            entry.clear();
            throw new ProviderException("PROVIDER_KEY_MISSING");
        }
        return new ConsumedSecret(entry.secret);
    }

    public int pendingCount() { purgeExpired(); return sessions.size(); }

    private void purgeExpired() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> {
            if (entry.getValue().expiresAt.isAfter(now)) return false;
            entry.getValue().clear();
            return true;
        });
    }

    @Override public void close() {
        sessions.values().forEach(Entry::clear);
        sessions.clear();
    }

    public record SecretSession(String secretSessionId, Instant expiresAt) {}

    public static final class ConsumedSecret implements AutoCloseable {
        private final char[] value;
        private final AtomicBoolean closed = new AtomicBoolean();
        private ConsumedSecret(char[] value) { this.value = value; }
        public String value() {
            if (closed.get()) throw new IllegalStateException("Secret session is closed");
            return new String(value);
        }
        @Override public void close() { if (closed.compareAndSet(false, true)) Arrays.fill(value, '\0'); }
    }

    private static final class Entry {
        private final String providerId;
        private final char[] secret;
        private final Instant expiresAt;
        private Entry(String providerId, char[] secret, Instant expiresAt) {
            this.providerId = providerId; this.secret = secret; this.expiresAt = expiresAt;
        }
        private void clear() { Arrays.fill(secret, '\0'); }
    }
}
