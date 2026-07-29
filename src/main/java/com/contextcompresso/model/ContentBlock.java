package com.contextcompresso.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Typed shape for an Anthropic-style content block (e.g. {"type":"text","text":"..."} or
 * {"type":"code","text":"..."}). The compression pipeline primarily walks JsonNode trees
 * directly, but this is useful for focused unit tests (e.g. TextTruncator).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentBlock(
        String type,
        String text,
        String language
) {
}
