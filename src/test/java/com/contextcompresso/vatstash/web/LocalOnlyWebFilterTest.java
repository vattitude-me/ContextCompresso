package com.contextcompresso.vatstash.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LocalOnlyWebFilterTest {

    private final LocalOnlyWebFilter filter = new LocalOnlyWebFilter();

    private static InetSocketAddress addr(String ip) throws Exception {
        return new InetSocketAddress(InetAddress.getByName(ip), 50000);
    }

    private MockServerWebExchange run(String path, InetSocketAddress remote, String origin, AtomicInteger passed) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get(path);
        if (remote != null) {
            builder.remoteAddress(remote);
        }
        if (origin != null) {
            builder.header("Origin", origin);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.build());
        WebFilterChain chain = ex -> {
            passed.incrementAndGet();
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return exchange;
    }

    @Test
    void rejectsNonLoopback() throws Exception {
        AtomicInteger passed = new AtomicInteger();

        MockServerWebExchange lan = run("/vatstash/search", addr("192.168.1.20"), null, passed);
        assertThat(lan.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(lan.getResponse().getBodyAsString().block()).contains("\"error\":\"forbidden\"");

        MockServerWebExchange mcp = run("/mcp", addr("10.0.0.5"), null, passed);
        assertThat(mcp.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        MockServerWebExchange unknown = run("/vatstash/workspaces", null, null, passed);
        assertThat(unknown.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(passed.get()).isZero();

        MockServerWebExchange local4 = run("/vatstash/workspaces", addr("127.0.0.1"), null, passed);
        MockServerWebExchange local6 = run("/mcp", addr("::1"), null, passed);
        assertThat(local4.getResponse().getStatusCode()).isNull();
        assertThat(local6.getResponse().getStatusCode()).isNull();
        assertThat(passed.get()).isEqualTo(2);
    }

    @Test
    void rejectsForeignOrigin() throws Exception {
        AtomicInteger passed = new AtomicInteger();

        for (String origin : new String[]{"https://evil.example", "http://localhost.evil.example",
                "http://127.0.0.1.nip.io", "null", "http://192.168.1.2:3000"}) {
            MockServerWebExchange ex = run("/vatstash/search", addr("127.0.0.1"), origin, passed);
            assertThat(ex.getResponse().getStatusCode()).as(origin).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(passed.get()).isZero();
    }

    @Test
    void allowsVscodeWebviewAndLocalhostOrigins() throws Exception {
        AtomicInteger passed = new AtomicInteger();
        String[] origins = {"vscode-webview://1abc2def", "vscode-file://vscode-app", "http://localhost:3000",
                "https://localhost", "http://127.0.0.1:8137", "http://[::1]:9000"};

        for (String origin : origins) {
            MockServerWebExchange ex = run("/vatstash/metrics/summary", addr("127.0.0.1"), origin, passed);
            assertThat(ex.getResponse().getStatusCode()).as(origin).isNull();
        }
        assertThat(passed.get()).isEqualTo(origins.length);
    }

    @Test
    void ignoresOtherPaths() throws Exception {
        AtomicInteger passed = new AtomicInteger();

        for (String path : new String[]{"/v1/messages", "/v1/chat/completions", "/actuator/health",
                "/vatstashx", "/mcpx", "/"}) {
            MockServerWebExchange ex = run(path, addr("192.168.1.20"), "https://evil.example", passed);
            assertThat(ex.getResponse().getStatusCode()).as(path).isNull();
        }
        assertThat(passed.get()).isEqualTo(6);
    }
}
