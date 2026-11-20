package com.contextcompresso.vatstash.chunk;

import java.util.List;

public interface Chunker {
    List<CodeChunk> chunk(String relPath, String language, String content);
}
