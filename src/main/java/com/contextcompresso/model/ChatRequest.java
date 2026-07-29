package com.contextcompresso.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequest(
        String model,
        List<ChatMessage> messages,
        Boolean stream
) {
}
