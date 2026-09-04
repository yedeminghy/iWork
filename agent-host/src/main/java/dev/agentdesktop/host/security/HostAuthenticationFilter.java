package dev.agentdesktop.host.security;

import dev.agentdesktop.host.bootstrap.BootstrapConfig;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class HostAuthenticationFilter implements WebFilter {
    public static final String PROTOCOL_HEADER = "X-App-Protocol";
    private final BootstrapConfig bootstrap;

    public HostAuthenticationFilter(BootstrapConfig bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isLoopback(exchange.getRequest().getRemoteAddress())) {
            return reject(exchange, HttpStatus.FORBIDDEN);
        }
        String protocol = exchange.getRequest().getHeaders().getFirst(PROTOCOL_HEADER);
        if (!Integer.toString(bootstrap.protocolVersion()).equals(protocol)) {
            return reject(exchange, HttpStatus.BAD_REQUEST);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String expected = "Bearer " + bootstrap.sessionToken();
        if (!constantTimeEquals(expected, authorization)) {
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }
        return chain.filter(exchange);
    }

    private static boolean isLoopback(InetSocketAddress remoteAddress) {
        if (remoteAddress == null) return true;
        InetAddress address = remoteAddress.getAddress();
        return address != null && address.isLoopbackAddress();
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}

