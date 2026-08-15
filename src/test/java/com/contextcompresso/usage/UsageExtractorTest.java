package com.contextcompresso.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class UsageExtractorTest {

    private final UsageExtractor extractor = new UsageExtractor(new ObjectMapper());

    @Test
    void extractsAnthropicUsageFromNonStreamingBody() {
        String body = """
                {
                  "id": "msg_1",
                  "model": "claude-opus-5",
                  "usage": {
                    "input_tokens": 2095,
                    "output_tokens": 503,
                    "cache_creation_input_tokens": 18204,
                    "cache_read_input_tokens": 154200
                  }
                }
                """;
        UsageExtractor.ExtractedUsage usage = extractor.fromJsonBody(body.getBytes(StandardCharsets.UTF_8));

        assertThat(usage.inputTokens()).isEqualTo(2095);
        assertThat(usage.outputTokens()).isEqualTo(503);
        assertThat(usage.cacheReadTokens()).isEqualTo(154200);
        assertThat(usage.cacheWriteTokens()).isEqualTo(18204);
        assertThat(usage.model()).isEqualTo("claude-opus-5");
    }

    @Test
    void extractsOpenAiUsageFromNonStreamingBody() {
        String body = """
                {
                  "id": "chatcmpl-1",
                  "model": "gpt-4o",
                  "usage": {
                    "prompt_tokens": 1200,
                    "completion_tokens": 340,
                    "prompt_tokens_details": { "cached_tokens": 900 }
                  }
                }
                """;
        UsageExtractor.ExtractedUsage usage = extractor.fromJsonBody(body.getBytes(StandardCharsets.UTF_8));

        assertThat(usage.inputTokens()).isEqualTo(1200);
        assertThat(usage.outputTokens()).isEqualTo(340);
        assertThat(usage.cacheReadTokens()).isEqualTo(900);
        assertThat(usage.cacheWriteTokens()).isZero();
    }

    @Test
    void returnsEmptyForMalformedJson() {
        UsageExtractor.ExtractedUsage usage = extractor.fromJsonBody("not json{{".getBytes(StandardCharsets.UTF_8));
        assertThat(usage.isEmpty()).isTrue();
    }

    @Test
    void returnsEmptyWhenUsageBlockAbsent() {
        String body = "{\"id\": \"msg_1\", \"model\": \"claude-opus-5\"}";
        UsageExtractor.ExtractedUsage usage = extractor.fromJsonBody(body.getBytes(StandardCharsets.UTF_8));
        assertThat(usage.isEmpty()).isTrue();
    }

    @Test
    void extractsAnthropicUsageFromSseEventsAcrossMultipleFrames() {
        String sse = """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","model":"claude-opus-5","usage":{"input_tokens":500,"output_tokens":1,"cache_read_input_tokens":12000,"cache_creation_input_tokens":0}}}

                event: content_block_delta
                data: {"type":"content_block_delta","delta":{"text":"hi"}}

                event: message_delta
                data: {"type":"message_delta","usage":{"output_tokens":250}}

                event: message_stop
                data: {"type":"message_stop"}
                """;
        UsageExtractor.ExtractedUsage usage = extractor.fromSseChunk(sse);

        assertThat(usage.inputTokens()).isEqualTo(500);
        assertThat(usage.cacheReadTokens()).isEqualTo(12000);
        // message_delta's output_tokens (250) supersedes message_start's (1); merge takes the max
        assertThat(usage.outputTokens()).isEqualTo(250);
        assertThat(usage.model()).isEqualTo("claude-opus-5");
    }

    @Test
    void ignoresDoneSentinelAndPartialFrames() {
        String sse = "data: [DONE]\ndata: {not valid json\n";
        UsageExtractor.ExtractedUsage usage = extractor.fromSseChunk(sse);
        assertThat(usage.isEmpty()).isTrue();
    }

    @Test
    void extractsOpenAiUsageFromFinalSseChunk() {
        String sse = """
                data: {"choices":[{"delta":{"content":"hi"}}]}

                data: {"choices":[],"usage":{"prompt_tokens":800,"completion_tokens":120,"prompt_tokens_details":{"cached_tokens":600}}}

                data: [DONE]
                """;
        UsageExtractor.ExtractedUsage usage = extractor.fromSseChunk(sse);

        assertThat(usage.inputTokens()).isEqualTo(800);
        assertThat(usage.outputTokens()).isEqualTo(120);
        assertThat(usage.cacheReadTokens()).isEqualTo(600);
    }
}
