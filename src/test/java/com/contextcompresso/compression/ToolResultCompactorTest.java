package com.contextcompresso.compression;

import com.contextcompresso.tokenizer.CharRatioEstimator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultCompactorTest {

    private final ToolResultCompactor compactor = new ToolResultCompactor();
    private final CharRatioEstimator estimator = new CharRatioEstimator();

    @Test
    void shortToolResultPassesThroughUnchanged() {
        String text = "file.txt: 3 matches found.";
        String result = compactor.compact(text, 1000, estimator);
        assertThat(result).isEqualTo(text);
    }

    @Test
    void longGrepDumpIsCompacted() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            sb.append("src/file").append(i).append(".java:12: match on line ").append(i).append('\n');
        }
        String text = sb.toString();
        String result = compactor.compact(text, 20, estimator);

        assertThat(result.length()).isLessThan(text.length());
        assertThat(result).contains("lines omitted");
    }

    @Test
    void preservesTailWhereErrorsTypicallyLive() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("Running test case ").append(i).append('\n');
        }
        sb.append("FAILURE: assertion failed at line 42.\n");
        String text = sb.toString();

        String result = compactor.compact(text, 15, estimator);
        assertThat(result).contains("FAILURE: assertion failed at line 42.");
    }

    @Test
    void preservesHeadWhereCommandIdentityTypicallyLives() {
        StringBuilder sb = new StringBuilder();
        sb.append("$ grep -rn TODO src/\n");
        for (int i = 0; i < 300; i++) {
            sb.append("src/file").append(i).append(".java:1: TODO placeholder\n");
        }
        String text = sb.toString();

        String result = compactor.compact(text, 15, estimator);
        assertThat(result).contains("$ grep -rn TODO src/");
    }

    @Test
    void unbrokenSingleLineTextIsLeftUntouched() {
        // no line structure to exploit — must not attempt a blind char-based cut
        String text = "x".repeat(5000);
        String result = compactor.compact(text, 10, estimator);
        assertThat(result).isEqualTo(text);
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertThat(compactor.compact(null, 100, estimator)).isNull();
        assertThat(compactor.compact("", 100, estimator)).isEmpty();
    }
}
