package com.contextcompresso.vatstash.metrics;

import com.contextcompresso.config.VatStashProperties;
import com.contextcompresso.vatstash.VatStashTestSupport;
import com.contextcompresso.vatstash.VatStashTestSupport.Env;
import com.contextcompresso.vatstash.search.SearchRequest;
import com.contextcompresso.vatstash.search.SearchResponse;
import com.contextcompresso.vatstash.search.SearchTotals;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VatStashMetricsServiceTest {

    private static final long HOUR = 3_600_000L;

    @TempDir
    Path tmp;

    Env env;
    VatStashMetricsService metrics;

    @BeforeEach
    void setUp() throws IOException {
        env = VatStashTestSupport.env(tmp);
        metrics = new VatStashMetricsService(env.store(), env.props());
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private static SearchEvent event(String id, String client, int results, int files, long returned, long source,
                                     long tookMs, long createdAt) {
        return new SearchEvent(id, "ws1", client, 12, "hash" + id, null, results, files, returned, source, tookMs,
                "fresh", createdAt);
    }

    @Test
    void summaryAggregatesByClientAndDay() {
        long now = System.currentTimeMillis();
        env.store().insert(event("1", "mcp", 4, 2, 400, 4000, 20, now - HOUR / 2));
        env.store().insert(event("2", "mcp", 0, 0, 30, 0, 10, now - HOUR / 2));
        env.store().insert(event("3", "vscode", 2, 1, 200, 2000, 30, now - 25 * HOUR));
        env.store().insert(event("4", "mcp", 1, 1, 1, 1, 5, now - 40 * 24 * HOUR));

        MetricsSummary s = metrics.summary(7);

        assertThat(s.days()).isEqualTo(7);
        assertThat(s.calls()).isEqualTo(3);
        assertThat(s.byClient()).containsEntry("mcp", 2).containsEntry("vscode", 1).hasSize(2);
        assertThat(s.returnedTokens()).isEqualTo(630);
        assertThat(s.sourceFileTokens()).isEqualTo(6000);
        assertThat(s.estimatedReductionRatio()).isEqualTo(0.895);
        assertThat(s.avgChunksPerCall()).isEqualTo(2.0);
        assertThat(s.zeroResultCalls()).isEqualTo(1);
        assertThat(s.byDay()).hasSize(2);
        assertThat(s.byDay().stream().mapToInt(MetricsSummary.Day::calls).sum()).isEqualTo(3);
        assertThat(s.byDay()).isSortedAccordingTo((a, b) -> a.day().compareTo(b.day()));
        assertThat(s.note()).isEqualTo(VatStashMetricsService.NOTE);
        assertThat(metrics.summary(90).calls()).isEqualTo(4);
        assertThat(metrics.summary(0).days()).isEqualTo(1);
        assertThat(metrics.summary(1000).days()).isEqualTo(90);
    }

    @Test
    void percentilesComputed() {
        long now = System.currentTimeMillis();
        for (int i = 1; i <= 10; i++) {
            env.store().insert(event("p" + i, "http", 1, 1, 10, 100, i * 10L, now - i * 1000L));
        }

        MetricsSummary s = metrics.summary(1);

        assertThat(s.p50LatencyMs()).isEqualTo(50);
        assertThat(s.p95LatencyMs()).isEqualTo(100);
        assertThat(metrics.summary(7).calls()).isEqualTo(10);
    }

    @Test
    void reductionRatioZeroWhenNoSource() {
        MetricsSummary empty = metrics.summary(7);
        assertThat(empty.calls()).isZero();
        assertThat(empty.estimatedReductionRatio()).isZero();
        assertThat(empty.p50LatencyMs()).isZero();
        assertThat(empty.avgChunksPerCall()).isZero();

        env.store().insert(event("z", "http", 0, 0, 25, 0, 3, System.currentTimeMillis()));
        MetricsSummary s = metrics.summary(7);
        assertThat(s.sourceFileTokens()).isZero();
        assertThat(s.estimatedReductionRatio()).isZero();
    }

    @Test
    void csvEscapesAndHasHeader() {
        long now = System.currentTimeMillis();
        env.store().insert(new SearchEvent("c1", "ws1,ws2", "we\"ird", 5, "h", null, 1, 1, 10, 100, 7, "stale", now));
        env.store().insert(event("c2", "plain", 2, 1, 20, 200, 8, now));

        String csv = metrics.exportCsv(7);
        String[] lines = csv.split("\r\n", -1);

        assertThat(lines[0]).isEqualTo("created_at_iso,client,workspace_ids,query_chars,results,files,"
                + "returned_tokens,source_file_tokens,took_ms,freshness");
        assertThat(lines).hasSize(4);
        assertThat(lines[3]).isEmpty();
        assertThat(csv).contains(",\"we\"\"ird\",\"ws1,ws2\",5,1,1,10,100,7,stale");
        assertThat(csv).contains(",plain,ws1,12,2,1,20,200,8,fresh");
        assertThat(csv).doesNotContain("query_text").doesNotContain("hashc1");
        assertThat(lines[1]).matches("\\d{4}-\\d{2}-\\d{2}T.*Z,.*");
    }

    @Test
    void markdownContainsEstimateNote() {
        env.store().insert(event("m1", "mcp", 3, 2, 300, 3000, 15, System.currentTimeMillis()));

        String md = metrics.exportMarkdown(7);

        assertThat(md).startsWith("# VatStash usage, last 7 days");
        assertThat(md).contains("| Calls | 1 |").contains("| Estimated reduction | 90.0% |");
        assertThat(md).contains("| Calls from mcp | 1 |");
        assertThat(md).contains(VatStashMetricsService.NOTE).contains("estimate");
    }

    @Test
    void queryTextOnlyStoredWhenEnabled() {
        SearchRequest request = new SearchRequest("secret query", List.of("ws1"), null, null, null, null, null, null);
        SearchResponse response = new SearchResponse("sid-1", "secret query", 12, "fresh", List.of(),
                new SearchTotals(50, 500, 1, 2, 0), "text");

        metrics.record(request, response, List.of("ws1"));
        List<SearchEvent> stored = env.store().findSince(0);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).queryText()).isNull();
        assertThat(stored.get(0).queryHash()).isNotBlank().doesNotContain("secret");
        assertThat(stored.get(0).queryChars()).isEqualTo("secret query".length());
        assertThat(stored.get(0).client()).isEqualTo("http");
        assertThat(stored.get(0).returnedTokens()).isEqualTo(50);
        assertThat(stored.get(0).sourceFileTokens()).isEqualTo(500);

        VatStashProperties storing = VatStashTestSupport.withStoreQueryText(env.props(), true);
        VatStashMetricsService withText = new VatStashMetricsService(env.store(), storing);
        SearchResponse second = new SearchResponse("sid-2", "secret query", 12, "fresh", List.of(),
                new SearchTotals(50, 500, 1, 2, 0), "text");
        withText.record(new SearchRequest("secret query", List.of("ws1"), null, null, null, null, null, "mcp"), second,
                List.of("ws1"));

        assertThat(env.store().findSince(0)).filteredOn(e -> e.id().equals("sid-2"))
                .singleElement().satisfies(e -> {
                    assertThat(e.queryText()).isEqualTo("secret query");
                    assertThat(e.client()).isEqualTo("mcp");
                });
    }
}
