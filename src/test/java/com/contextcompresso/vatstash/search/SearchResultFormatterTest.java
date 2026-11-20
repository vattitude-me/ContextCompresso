package com.contextcompresso.vatstash.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchResultFormatterTest {

    private static SearchHit hit(String path, int start, int end, String symbol, String content) {
        return new SearchHit("ws1", path, "/root/" + path, "java", symbol, "method", start, end, 1.0, content, 10);
    }

    @Test
    void startsWithMarkerAndListsPathLineRanges() {
        List<SearchHit> hits = List.of(
                hit("src/A.java", 10, 14, "A.run", "    void run() {\n    }"),
                hit("src/B.java", 3, 8, "B.stop", "    void stop() {\n    }"));
        var info = new SearchResultFormatter.IndexInfo("ready", 42, 42, 42, List.of("/root"));

        String text = SearchResultFormatter.format("run the thing", hits, 0, 4000, info);

        assertThat(text).startsWith(SearchResultFormatter.MARKER);
        assertThat(text).startsWith("[vatstash v1] 2 snippets from 2 files for: run the thing");
        assertThat(text).contains("index: fresh").contains("42 files").contains("/root");
        assertThat(text).contains("--- src/A.java:10-14 · java · method A.run");
        assertThat(text).contains("--- src/B.java:3-8 · java · method B.stop");
        assertThat(text).contains("    void run() {");
        assertThat(text).doesNotContain("```");
        assertThat(text).doesNotContain("omitted");

        String omitted = SearchResultFormatter.format("q", hits, 3, 500, info);
        assertThat(omitted).contains("(3 more matches omitted to stay within the 500-token budget");
    }

    @Test
    void zeroResultsMessage() {
        var fresh = new SearchResultFormatter.IndexInfo("ready", 5, 5, 5, List.of("/root"));
        String text = SearchResultFormatter.format("nothing here", List.of(), 0, 4000, fresh);

        assertThat(text).startsWith("[vatstash v1] No matches for: nothing here");
        assertThat(text).contains("fall back to your normal search tools");
        assertThat(text).doesNotContain("index:");

        var building = new SearchResultFormatter.IndexInfo("building", 5, 5, 20, List.of("/root"));
        String stale = SearchResultFormatter.format("nothing here", List.of(), 0, 4000, building);
        assertThat(stale).startsWith(SearchResultFormatter.MARKER).contains("index: building 5/20 files");
    }
}
