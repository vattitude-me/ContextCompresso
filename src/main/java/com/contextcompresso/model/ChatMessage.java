package com.contextcompresso.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatMessage(
        String role,
        String content,
        List<ContentBlock> contentBlocks
) {
}
