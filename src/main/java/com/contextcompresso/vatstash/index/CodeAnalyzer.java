package com.contextcompresso.vatstash.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.StopFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;

import java.util.Arrays;

/** CodeTokenizer, lower-casing, then a small English stop list. */
public final class CodeAnalyzer extends Analyzer {

    private static final CharArraySet STOP_WORDS = new CharArraySet(Arrays.asList(
            "the", "a", "an", "and", "or", "of", "to", "in", "on", "for", "is", "are", "how", "where", "what",
            "which", "does", "do", "with", "from", "by", "this", "that", "it", "be"), true);

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer source = new CodeTokenizer();
        TokenStream stream = new LowerCaseFilter(source);
        stream = new StopFilter(stream, STOP_WORDS);
        return new TokenStreamComponents(source, stream);
    }
}
