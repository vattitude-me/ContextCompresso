package com.contextcompresso.tokenizer;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Anthropic has not published a Java tokenizer, so this uses a calibrated char-ratio
 * heuristic: chars/3.2 for English prose, chars/2.5 for code (code tokenizes denser
 * due to punctuation/symbols). "Looks like code" is a cheap heuristic based on the
 * density of code-signal characters/tokens rather than a full parse.
 */
@Component
public class AnthropicEstimator implements TokenEstimator {

    private static final double ENGLISH_CHARS_PER_TOKEN = 3.2;
    private static final double CODE_CHARS_PER_TOKEN = 2.5;

    private static final Pattern CODE_SIGNAL = Pattern.compile(
            "[{};()\\[\\]=<>]|\\bfunction\\b|\\bdef \\b|\\bclass \\b|\\bimport \\b|=>|::");

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double charsPerToken = looksLikeCode(text) ? CODE_CHARS_PER_TOKEN : ENGLISH_CHARS_PER_TOKEN;
        return (int) Math.ceil(text.length() / charsPerToken);
    }

    private boolean looksLikeCode(String text) {
        int sampleLen = Math.min(text.length(), 2000);
        String sample = text.substring(0, sampleLen);
        long signalCount = CODE_SIGNAL.matcher(sample).results().count();
        // more than one code-signal token per ~40 chars suggests code
        return signalCount > 0 && (double) signalCount / sampleLen > (1.0 / 40.0);
    }
}
