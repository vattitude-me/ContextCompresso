package com.contextcompresso.vatstash.chunk;

import com.contextcompresso.config.VatStashProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/** Picks the chunker for a language; every chunker failure degrades to the heuristic chunker. */
@Component
public class ChunkerRegistry {

    private final HeuristicChunker heuristic;
    private final JavaParserChunker java;

    public ChunkerRegistry(VatStashProperties props) {
        this.heuristic = new HeuristicChunker(props);
        this.java = new JavaParserChunker(heuristic, props.maxChunkLines());
    }

    public Chunker forLanguage(String language) {
        return "java".equals(language) ? java : heuristic;
    }

    public List<CodeChunk> chunk(String relPath, String language, String content) {
        try {
            return forLanguage(language).chunk(relPath, language, content);
        } catch (RuntimeException e) {
            return heuristic.chunk(relPath, language, content);
        }
    }
}
