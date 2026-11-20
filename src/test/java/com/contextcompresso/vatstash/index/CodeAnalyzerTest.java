package com.contextcompresso.vatstash.index;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeAnalyzerTest {

    private final Analyzer analyzer = new CodeAnalyzer();

    private List<String> tokens(String text) throws IOException {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream("content", text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                out.add(term.toString());
            }
            ts.end();
        }
        return out;
    }

    @Test
    void splitsCamelCaseKeepingWholeIdentifier() throws IOException {
        // "by" is on the stop list, so it is dropped from the sub-words
        assertThat(tokens("getUserById")).containsExactly("getuserbyid", "get", "user", "id");
        assertThat(tokens("HTTPServer")).containsExactly("httpserver", "http", "server");
        assertThat(tokens("PaymentService")).containsExactly("paymentservice", "payment", "service");
        assertThat(tokens("retryCharge")).containsExactly("retrycharge", "retry", "charge");
    }

    @Test
    void splitsSnakeAndScreamingCase() throws IOException {
        assertThat(tokens("max_retry_count")).containsExactly("max_retry_count", "max", "retry", "count");
        assertThat(tokens("MAX_RETRY_COUNT")).containsExactly("max_retry_count", "max", "retry", "count");
        assertThat(tokens("sha256Hash")).contains("sha256hash", "sha", "256", "hash");
    }

    @Test
    void dropsEnglishStopwords() throws IOException {
        assertThat(tokens("how does the cache work")).containsExactly("cache", "work");
        assertThat(tokens("where is the retry for payments")).containsExactly("retry", "payments");
        assertThat(tokens("what is this")).isEmpty();
    }
}
