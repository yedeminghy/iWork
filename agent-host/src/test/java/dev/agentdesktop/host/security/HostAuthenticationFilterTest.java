package dev.agentdesktop.host.security;

import dev.agentdesktop.host.bootstrap.BootstrapConfig;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class HostAuthenticationFilterTest {
    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private final HostAuthenticationFilter filter = new HostAuthenticationFilter(
            new BootstrapConfig(1, TOKEN, Path.of(".")));

    @Test
    void rejectsMissingToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/internal/health")
                .header(HostAuthenticationFilter.PROTOCOL_HEADER, "1"));
        filter.filter(exchange, ignored -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void acceptsMatchingProtocolAndToken() {
        AtomicBoolean invoked = new AtomicBoolean();
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/internal/health")
                .header(HostAuthenticationFilter.PROTOCOL_HEADER, "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));
        filter.filter(exchange, ignored -> Mono.fromRunnable(() -> invoked.set(true))).block();
        assertThat(invoked).isTrue();
    }

    @Test
    void rejectsWrongProtocol() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/internal/health")
                .header(HostAuthenticationFilter.PROTOCOL_HEADER, "2")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));
        filter.filter(exchange, ignored -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void rejectsWrongToken() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/internal/health")
                .header(HostAuthenticationFilter.PROTOCOL_HEADER, "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"));
        filter.filter(exchange, ignored -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsNonLoopbackCaller() throws Exception {
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByName("192.0.2.10"), 45123);
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/internal/health")
                .remoteAddress(remote)
                .header(HostAuthenticationFilter.PROTOCOL_HEADER, "1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));
        filter.filter(exchange, ignored -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }
}
