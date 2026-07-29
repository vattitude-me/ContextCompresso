package com.contextcompresso.provider;

import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

@Component
public class ProviderDetector {

    public Provider detect(ServerHttpRequest request) {
        String explicit = request.getHeaders().getFirst("X-CC-Provider");
        if (explicit != null) {
            try {
                return Provider.valueOf(explicit.toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // fall through to heuristics on unrecognized override value
            }
        }

        String auth = request.getHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ghp_")) {
            return Provider.COPILOT;
        }
        if (request.getHeaders().containsKey("Copilot-Integration-Id")) {
            return Provider.COPILOT;
        }

        String apiKey = request.getHeaders().getFirst("X-Api-Key");
        if (apiKey != null && apiKey.startsWith("sk-ant-")) {
            return Provider.CLAUDE;
        }

        if (request.getPath().toString().contains("/v1/messages")) {
            return Provider.CLAUDE;
        }

        return Provider.DEFAULT;
    }
}
