package com.contextcompresso.compression;

import com.contextcompresso.tokenizer.TokenEstimator;
import org.springframework.stereotype.Component;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Head+tail truncation for oversized text content. Uses BreakIterator's sentence
 * instance (not a naive "split on '. '") so abbreviations like "Dr. Smith" don't
 * produce a false sentence boundary.
 */
@Component
public class TextTruncator {

    public String truncate(String text, int tokenThreshold, int headSentences, int tailSentences,
                            TokenEstimator estimator) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        int estimatedTokens = estimator.estimate(text);
        if (estimatedTokens <= tokenThreshold) {
            return text;
        }

        List<String> sentences = splitSentences(text);
        if (sentences.size() <= headSentences + tailSentences) {
            return text;
        }

        List<String> head = sentences.subList(0, headSentences);
        List<String> tail = sentences.subList(sentences.size() - tailSentences, sentences.size());

        String headText = String.join("", head);
        String tailText = String.join("", tail);
        int omittedChars = text.length() - headText.length() - tailText.length();

        StringBuilder result = new StringBuilder();
        result.append(headText.stripTrailing());
        result.append(" [...~").append(Math.max(omittedChars, 0)).append(" chars omitted...] ");
        result.append(tailText.stripLeading());
        return result.toString();
    }

    public List<String> splitSentences(String text) {
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.US);
        iterator.setText(text);
        List<String> sentences = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            sentences.add(text.substring(start, end));
        }
        return sentences;
    }
}
