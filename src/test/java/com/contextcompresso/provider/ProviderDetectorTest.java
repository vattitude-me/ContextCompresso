package com.contextcompresso.provider;

import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderDetectorTest {

    private final ProviderDetector detector = new ProviderDetector();

    @Test
    void ghpTokenDetectsCopilot() {
        ServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions")
                .header("Authorization", "Bearer ghp_abc123")
                .build();
        assertThat(detector.detect(request)).isEqualTo(Provider.COPILOT);
    }

    @Test
    void copilotIntegrationIdHeaderDetectsCopilot() {
        ServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions")
                .header("Copilot-Integration-Id", "vscode-chat")
                .build();
        assertThat(detector.detect(request)).isEqualTo(Provider.COPILOT);
    }

    @Test
    void skAntKeyDetectsClaude() {
        ServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions")
                .header("X-Api-Key", "sk-ant-abc123")
                .build();
        assertThat(detector.detect(request)).isEqualTo(Provider.CLAUDE);
    }

    // Uses a non-/v1/messages path on purpose: the path fallback would return CLAUDE anyway,
    // so routing through it would pass even with the Authorization rule removed.
    @Test
    void oauthBearerDetectsClaude() {
        ServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions")
                .header("Authorization", "Bearer sk-ant-oat01-abc123")
                .build();
        assertThat(detector.detect(request)).isEqualTo(Provider.CLAUDE);
    }

    @Test
    void ghpTokenStillWinsOverSkAntPrefixCheck() {
        ServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions")
                .header("Authorization", "Bearer ghp_abc123")
                .build();
        assertThat(detector.detect(request)).isEqualTo(Provider.COPILOT);
    }

    @Test
    void messagesPathDetectsClaude() {
        ServerHttpRequest request = MockServerHttpRequest.post("/v1/messages").build();
        assertThat(detector.detect(request)).isEqualTo(Provider.CLAUDE);
    }

    @Test
    void explicitHeaderOverridesHeuristics() {
        ServerHttpRequest request = MockServerHttpRequest.post("/v1/messages")
                .header("X-CC-Provider", "COPILOT")
                .header("X-Api-Key", "sk-ant-abc123")
                .build();
        assertThat(detector.detect(request)).isEqualTo(Provider.COPILOT);
    }

    @Test
    void noSignalsFallsBackToDefault() {
        ServerHttpRequest request = MockServerHttpRequest.post("/v1/chat/completions").build();
        assertThat(detector.detect(request)).isEqualTo(Provider.DEFAULT);
    }
}
