package com.contextcompresso.vatstash.search;

import java.util.List;

/**
 * Builds the {@code text} field of a search response: the exact text a model receives. It always
 * starts with {@link #MARKER}, which the compression pipeline uses to leave the text untouched.
 * No Markdown fences are used because source code contains backticks.
 */
public final class SearchResultFormatter {

    public static final String MARKER = "[vatstash v1]";

    private SearchResultFormatter() {
    }

    /** State of the index behind a search: {@code state} is ready, building or updating. */
    public record IndexInfo(String state, int filesIndexed, int done, int total, List<String> roots) {
        boolean fresh() {
            return "ready".equals(state);
        }
    }

    public static String format(String query, List<SearchHit> hits, int omittedByBudget, int maxTokens,
                                IndexInfo info) {
        StringBuilder sb = new StringBuilder();
        if (hits.isEmpty()) {
            sb.append(MARKER).append(" No matches for: ").append(query)
                    .append(". Try identifiers (class/method/config names) or fall back to your normal search tools.");
            if (info != null && !info.fresh()) {
                sb.append('\n').append(staleLine(info));
            }
            return sb.toString();
        }
        long files = hits.stream().map(h -> h.workspaceId() + "\u0000" + h.path()).distinct().count();
        sb.append(MARKER).append(' ').append(hits.size()).append(hits.size() == 1 ? " snippet from " : " snippets from ")
                .append(files).append(files == 1 ? " file" : " files").append(" for: ").append(query).append('\n');
        if (info != null) {
            sb.append(info.fresh() ? freshLine(info) : staleLine(info)).append('\n');
        }
        for (SearchHit h : hits) {
            sb.append('\n').append("--- ").append(h.path()).append(':').append(h.startLine()).append('-')
                    .append(h.endLine()).append(" · ").append(h.language()).append(" · ").append(h.kind());
            if (h.symbol() != null && !h.symbol().isBlank()) {
                sb.append(' ').append(h.symbol());
            }
            sb.append('\n').append(h.content());
            if (!h.content().endsWith("\n")) {
                sb.append('\n');
            }
        }
        if (omittedByBudget > 0) {
            sb.append('\n').append('(').append(omittedByBudget).append(" more ")
                    .append(omittedByBudget == 1 ? "match" : "matches")
                    .append(" omitted to stay within the ").append(maxTokens)
                    .append("-token budget; narrow the query or set pathPrefix.)");
        }
        return sb.toString().stripTrailing();
    }

    private static String freshLine(IndexInfo info) {
        return "index: fresh · " + info.filesIndexed() + " files · root " + String.join(", ", info.roots());
    }

    private static String staleLine(IndexInfo info) {
        return "index: " + info.state() + " " + info.done() + "/" + info.total()
                + " files — results may be incomplete";
    }
}
