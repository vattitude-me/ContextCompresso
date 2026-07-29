package com.contextcompresso.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Result of running the CompressionPipeline over a raw request body.
 */
public record CompressedRequest(
        JsonNode body,
        String requestId,
        CompressionStats stats,
        boolean streaming
) {
}
