package com.contextcompresso.vatstash.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Keeps the VatStash endpoints and the MCP endpoint reachable only from this machine. They index
 * arbitrary paths and return source code, so a LAN client or a web page (DNS rebinding) must not
 * be able to call them. Other paths, which are the proxy routes, are not touched.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class LocalOnlyWebFilter implements WebFilter {

    private static final Pattern ALLOWED_ORIGIN = Pattern.compile(
            "^(vscode-webview|vscode-file)://.*$|^https?://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:\\d+)?$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().pathWithinApplication().value();
        if (!isGuarded(path)) {
            return chain.filter(exchange);
        }
        InetSocketAddress remote = request.getRemoteAddress();
        if (remote == null || remote.getAddress() == null || !remote.getAddress().isLoopbackAddress()) {
            return forbidden(exchange, "VatStash is only available from this machine");
        }
        String origin = request.getHeaders().getFirst(HttpHeaders.ORIGIN);
        if (origin != null && !ALLOWED_ORIGIN.matcher(origin).matches()) {
            return forbidden(exchange, "Origin not allowed");
        }
        return chain.filter(exchange);
    }

    private static boolean isGuarded(String path) {
        return path.equals("/vatstash") || path.startsWith("/vatstash/")
                || path.equals("/mcp") || path.startsWith("/mcp/");
    }

    private static Mono<Void> forbidden(ServerWebExchange exchange, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"error\":\"forbidden\",\"message\":\"" + message + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }
}
