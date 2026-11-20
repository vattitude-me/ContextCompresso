package com.contextcompresso.vatstash.chunk;

/**
 * One indexable slice of a source file. Lines are 1-based and inclusive.
 * {@code kind} is one of type, method, constructor, members, block, section, window, file.
 */
public record CodeChunk(
        String path,
        String language,
        String symbol,
        String kind,
        int startLine,
        int endLine,
        String content
) {
}
