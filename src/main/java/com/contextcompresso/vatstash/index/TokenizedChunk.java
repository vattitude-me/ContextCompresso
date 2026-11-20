package com.contextcompresso.vatstash.index;

import com.contextcompresso.vatstash.chunk.CodeChunk;

public record TokenizedChunk(CodeChunk chunk, int tokens) {
}
