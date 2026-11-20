package com.contextcompresso.vatstash.metrics;

import com.contextcompresso.config.VatStashProperties;
import com.contextcompresso.util.HashUtil;
import com.contextcompresso.vatstash.search.SearchRequest;
import com.contextcompresso.vatstash.search.SearchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class VatStashMetricsService {

    public static final String NOTE = "sourceFileTokens is an estimate: the full size of the files the returned "
            + "snippets came from, assuming an agent would otherwise read them whole.";
    public static final String CSV_HEADER = "created_at_iso,client,workspace_ids,query_chars,results,files,"
            + "returned_tokens,source_file_tokens,took_ms,freshness";
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private static final Logger log = LoggerFactory.getLogger(VatStashMetricsService.class);

    private final SearchEventStore store;
    private final VatStashProperties props;

    public VatStashMetricsService(SearchEventStore store, VatStashProperties props) {
        this.store = store;
        this.props = props;
    }

    /** Records one search. Never throws: metrics must not affect search. */
    public void record(SearchRequest request, SearchResponse response, List<String> workspaceIds) {
        try {
            String query = request.query() == null ? "" : request.query();
            String client = request.client() == null || request.client().isBlank() ? "http" : request.client();
            store.insert(new SearchEvent(
                    response.searchId(),
                    String.join(",", workspaceIds),
                    client,
                    query.length(),
                    HashUtil.sha256Hex(query),
                    props.storeQueryText() ? query : null,
                    response.totals().chunksReturned(),
                    response.totals().filesMatched(),
                    response.totals().returnedTokens(),
                    response.totals().sourceFileTokens(),
                    response.tookMs(),
                    response.freshness(),
                    System.currentTimeMillis()));
        } catch (RuntimeException e) {
            log.warn("VatStash could not record a search event: {}", e.getMessage());
        }
    }

    public MetricsSummary summary(int days) {
        int d = clampDays(days);
        long since = System.currentTimeMillis() - d * DAY_MS;
        List<SearchEvent> events = store.findSince(since);

        Map<String, Integer> byClient = new TreeMap<>();
        Map<String, long[]> byDay = new TreeMap<>();
        long returned = 0;
        long source = 0;
        long chunks = 0;
        int zero = 0;
        List<Long> latencies = new ArrayList<>();
        for (SearchEvent e : events) {
            byClient.merge(e.client(), 1, Integer::sum);
            returned += e.returnedTokens();
            source += e.sourceFileTokens();
            chunks += e.results();
            if (e.results() == 0) {
                zero++;
            }
            latencies.add(e.tookMs());
            String day = Instant.ofEpochMilli(e.createdAt()).atZone(ZoneOffset.UTC).toLocalDate().toString();
            long[] b = byDay.computeIfAbsent(day, k -> new long[3]);
            b[0]++;
            b[1] += e.returnedTokens();
            b[2] += e.sourceFileTokens();
        }
        latencies.sort(Comparator.naturalOrder());
        List<MetricsSummary.Day> dayList = new ArrayList<>();
        byDay.forEach((day, b) -> dayList.add(new MetricsSummary.Day(day, (int) b[0], b[1], b[2])));
        int calls = events.size();
        double ratio = source == 0 ? 0.0 : round(1.0 - (double) returned / source, 4);
        double avgChunks = calls == 0 ? 0.0 : round((double) chunks / calls, 2);
        return new MetricsSummary(since, d, calls, byClient, returned, source, ratio, avgChunks,
                percentile(latencies, 50), percentile(latencies, 95), zero, dayList, NOTE);
    }

    public String exportCsv(int days) {
        int d = clampDays(days);
        List<SearchEvent> events = store.findSince(System.currentTimeMillis() - d * DAY_MS);
        StringBuilder sb = new StringBuilder(CSV_HEADER).append("\r\n");
        for (SearchEvent e : events) {
            sb.append(csv(Instant.ofEpochMilli(e.createdAt()).toString())).append(',')
                    .append(csv(e.client())).append(',')
                    .append(csv(e.workspaceIds())).append(',')
                    .append(e.queryChars()).append(',')
                    .append(e.results()).append(',')
                    .append(e.files()).append(',')
                    .append(e.returnedTokens()).append(',')
                    .append(e.sourceFileTokens()).append(',')
                    .append(e.tookMs()).append(',')
                    .append(csv(e.freshness())).append("\r\n");
        }
        return sb.toString();
    }

    public String exportMarkdown(int days) {
        MetricsSummary s = summary(days);
        StringBuilder sb = new StringBuilder();
        sb.append("# VatStash usage, last ").append(s.days()).append(s.days() == 1 ? " day" : " days").append("\n\n");
        sb.append("| Metric | Value |\n|---|---|\n");
        sb.append("| Calls | ").append(s.calls()).append(" |\n");
        sb.append("| Returned tokens | ").append(s.returnedTokens()).append(" |\n");
        sb.append("| Source file tokens (estimate) | ").append(s.sourceFileTokens()).append(" |\n");
        sb.append("| Estimated reduction | ").append(Math.round(s.estimatedReductionRatio() * 1000) / 10.0)
                .append("% |\n");
        sb.append("| Average chunks per call | ").append(s.avgChunksPerCall()).append(" |\n");
        sb.append("| Latency p50 / p95 (ms) | ").append(s.p50LatencyMs()).append(" / ").append(s.p95LatencyMs())
                .append(" |\n");
        sb.append("| Zero-result calls | ").append(s.zeroResultCalls()).append(" |\n");
        s.byClient().forEach((client, n) -> sb.append("| Calls from ").append(client).append(" | ").append(n)
                .append(" |\n"));
        sb.append("\n").append(NOTE).append("\n");
        return sb.toString();
    }

    private static long percentile(List<Long> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static int clampDays(int days) {
        return Math.max(1, Math.min(90, days));
    }

    private static double round(double v, int places) {
        double f = Math.pow(10, places);
        return Math.round(v * f) / f;
    }

    static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
