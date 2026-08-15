package com.contextcompresso.compression;

import com.contextcompresso.tokenizer.TokenEstimator;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Aggressive truncation specifically for tool_result content — grep dumps, file reads,
 * command stdout. These sit after the system prompt / early-turn prefix that Anthropic's
 * prompt caching matches on, so compacting them does not invalidate the cache the way
 * rewriting the prefix would. This is the highest-value compression target for a team whose
 * credits drain from running commands rather than from long conversational prose: tool
 * output is usually the single largest and least information-dense content in a request.
 *
 * <p>Splits on lines rather than reusing {@link TextTruncator}'s sentence splitter: the
 * JDK's {@code BreakIterator} sentence rules require an uppercase (or otherwise
 * sentence-starting) character after ". " to register a boundary, so lowercase-continuation
 * text like "file.java:12: match. src/other.java:5: match." — exactly the shape of grep
 * output and file paths — collapses into a single "sentence" and never gets truncated at
 * all. Tool output is naturally line-delimited, so splitting there is both correct and
 * cheaper.
 */
@Component
public class ToolResultCompactor {

    // Asymmetric vs. TextTruncator's general prose defaults: tool output's head usually
    // carries just the command/file identity, while errors and final results live at the
    // tail, so the tail is worth preserving more of.
    private static final int HEAD_LINES = 5;
    private static final int TAIL_LINES = 10;

    /**
     * @param toolResultTokenThreshold token budget for tool_result content specifically,
     *                                 independent of (and typically smaller than) the general
     *                                 message threshold
     */
    public String compact(String toolResultText, int toolResultTokenThreshold, TokenEstimator estimator) {
        if (toolResultText == null || toolResultText.isEmpty()) {
            return toolResultText;
        }
        int estimatedTokens = estimator.estimate(toolResultText);
        if (estimatedTokens <= toolResultTokenThreshold) {
            return toolResultText;
        }

        List<String> lines = List.of(toolResultText.split("\n", -1));
        if (lines.size() <= HEAD_LINES + TAIL_LINES) {
            // no line structure to exploit (e.g. one giant unbroken line) — leave as-is
            // rather than guessing at a char-based cut that could sever mid-token content
            return toolResultText;
        }

        List<String> head = lines.subList(0, HEAD_LINES);
        List<String> tail = lines.subList(lines.size() - TAIL_LINES, lines.size());
        int omittedLines = lines.size() - HEAD_LINES - TAIL_LINES;

        String headText = String.join("\n", head);
        String tailText = String.join("\n", tail);

        return headText + "\n[...~" + omittedLines + " lines omitted...]\n" + tailText;
    }
}
