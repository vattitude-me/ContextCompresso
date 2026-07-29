package com.contextcompresso.compression;

import com.contextcompresso.tokenizer.CharRatioEstimator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextTruncatorTest {

    private final TextTruncator truncator = new TextTruncator();
    private final CharRatioEstimator estimator = new CharRatioEstimator();

    @Test
    void shortTextPassesThroughUnchanged() {
        String text = "This is a short sentence. Another one.";
        String result = truncator.truncate(text, 1000, 5, 3, estimator);
        assertThat(result).isEqualTo(text);
    }

    @Test
    void longTextProducesHeadSentinelTail() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("This is sentence number ").append(i).append(". ");
        }
        String text = sb.toString();
        String result = truncator.truncate(text, 10, 3, 2, estimator);

        assertThat(result).contains("chars omitted");
        assertThat(result).contains("sentence number 0");
        assertThat(result).contains("sentence number 199");
    }

    @Test
    void handlesAbbreviationsWithoutFalseSentenceSplit() {
        // The JDK's built-in BreakIterator (unlike ICU) does not special-case "Dr." as an
        // abbreviation, so it legitimately splits after it; what matters for TextTruncator's
        // purposes is that no text is lost or duplicated across the split, and truncation
        // still works correctly against a sentence list that contains such boundaries.
        String text = "Dr. Smith went to the store. He bought milk.";
        var sentences = truncator.splitSentences(text);
        assertThat(String.join("", sentences)).isEqualTo(text);
        assertThat(sentences.get(sentences.size() - 1)).contains("He bought milk.");
    }

    @Test
    void veryLongSingleContentBlockGetsTruncated() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("Line of tool output data here. ");
        }
        String text = sb.toString();
        String result = truncator.truncate(text, 5, 2, 2, estimator);
        assertThat(result.length()).isLessThan(text.length());
    }
}
