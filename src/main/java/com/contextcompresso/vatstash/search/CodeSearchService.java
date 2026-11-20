package com.contextcompresso.vatstash.search;

import com.contextcompresso.config.VatStashProperties;
import com.contextcompresso.tokenizer.JtokkitEstimator;
import com.contextcompresso.vatstash.VatStashException;
import com.contextcompresso.vatstash.index.FileState;
import com.contextcompresso.vatstash.index.IndexStatus;
import com.contextcompresso.vatstash.index.WorkspaceIndex;
import com.contextcompresso.vatstash.workspace.WorkspaceIndexer;
import com.contextcompresso.vatstash.workspace.WorkspaceRecord;
import com.contextcompresso.vatstash.workspace.WorkspaceRegistry;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiReader;
import org.apache.lucene.search.IndexSearcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs a query against one or more workspace indexes and shapes the answer for a model:
 * at most a few chunks per file, overlapping ranges merged, a token budget, and a ready-to-send text.
 */
@Component
public class CodeSearchService {

    private static final Logger log = LoggerFactory.getLogger(CodeSearchService.class);
    private static final int PER_SNIPPET_OVERHEAD_TOKENS = 12;
    private static final String TRUNCATED_LINE = "… (truncated)";

    private final WorkspaceRegistry registry;
    private final WorkspaceIndexer indexer;
    private final QueryBuilder queryBuilder;
    private final Retriever retriever;
    private final JtokkitEstimator estimator;
    private final VatStashProperties props;

    public CodeSearchService(WorkspaceRegistry registry, WorkspaceIndexer indexer, QueryBuilder queryBuilder,
                             Retriever retriever, JtokkitEstimator estimator, VatStashProperties props) {
        this.registry = registry;
        this.indexer = indexer;
        this.queryBuilder = queryBuilder;
        this.retriever = retriever;
        this.estimator = estimator;
        this.props = props;
    }

    /** The response plus the workspaces that were searched, which metrics need. */
    public record Outcome(SearchResponse response, List<String> workspaceIds) {
    }

    public SearchResponse search(SearchRequest request) {
        return searchWithContext(request).response();
    }

    public Outcome searchWithContext(SearchRequest request) {
        long started = System.nanoTime();
        if (request == null || request.query() == null || request.query().isBlank()) {
            throw VatStashException.invalidRequest("query must not be empty");
        }
        int maxResults = clamp(request.maxResults(), props.defaultMaxResults(), 1, props.maxResultsCap());
        int maxTokens = clamp(request.maxTokens(), props.defaultMaxTokens(), 1, props.maxTokensCap());
        ParsedQuery parsed = queryBuilder.parse(request.query(), request.pathPrefix(), request.languages());
        List<WorkspaceRecord> workspaces = resolve(request);

        List<WorkspaceIndex> indexes = new ArrayList<>();
        List<IndexSearcher> searchers = new ArrayList<>();
        try {
            for (WorkspaceRecord ws : workspaces) {
                boolean wasOpen = registry.isOpen(ws.workspaceId());
                WorkspaceIndex index = registry.openIndex(ws.workspaceId());
                registry.touch(ws.workspaceId());
                indexes.add(index);
                searchers.add(index.acquire());
                ensureFresh(ws, index, wasOpen);
            }
            return doSearch(request, parsed, workspaces, indexes, searchers, maxResults, maxTokens, started);
        } catch (IOException e) {
            throw VatStashException.unavailable("VatStash search failed: " + e.getMessage(), e);
        } finally {
            for (int i = 0; i < searchers.size(); i++) {
                indexes.get(i).release(searchers.get(i));
            }
        }
    }

    private Outcome doSearch(SearchRequest request, ParsedQuery parsed, List<WorkspaceRecord> workspaces,
                             List<WorkspaceIndex> indexes, List<IndexSearcher> searchers, int maxResults,
                             int maxTokens, long started) throws IOException {
        IndexSearcher searcher;
        int[] starts = new int[searchers.size()];
        if (searchers.size() == 1) {
            searcher = searchers.get(0);
        } else {
            IndexReader[] readers = new IndexReader[searchers.size()];
            int base = 0;
            for (int i = 0; i < readers.length; i++) {
                readers[i] = searchers.get(i).getIndexReader();
                starts[i] = base;
                base += readers[i].maxDoc();
            }
            searcher = new IndexSearcher(new MultiReader(readers, false));
        }

        int k = Math.max(maxResults * 4, maxResults);
        List<ScoredChunk> raw = retriever.retrieve(searcher, parsed, k);

        // Diversify: cap chunks per file and merge overlapping ranges.
        Map<String, List<Candidate>> byFile = new LinkedHashMap<>();
        List<Candidate> ordered = new ArrayList<>();
        for (ScoredChunk sc : raw) {
            int wsIdx = workspaceIndexOf(starts, sc.docId());
            Candidate cand = new Candidate(wsIdx, sc);
            String key = wsIdx + "\u0000" + sc.path();
            List<Candidate> inFile = byFile.computeIfAbsent(key, x -> new ArrayList<>());
            boolean merged = false;
            for (Candidate existing : inFile) {
                if (existing.tryMerge(cand, estimator)) {
                    merged = true;
                    break;
                }
            }
            if (merged) {
                continue;
            }
            if (inFile.size() >= props.maxChunksPerFile()) {
                continue;
            }
            inFile.add(cand);
            ordered.add(cand);
        }
        ordered.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed());
        List<Candidate> top = ordered.size() > maxResults ? ordered.subList(0, maxResults) : ordered;

        // Token budget, greedy in score order. The first result is always returned.
        List<Candidate> picked = new ArrayList<>();
        int used = 0;
        int omitted = 0;
        for (Candidate c : top) {
            int cost = c.tokens + PER_SNIPPET_OVERHEAD_TOKENS;
            if (used + cost <= maxTokens) {
                picked.add(c);
                used += cost;
            } else if (picked.isEmpty()) {
                c.truncateTo(Math.max(1, maxTokens - PER_SNIPPET_OVERHEAD_TOKENS), estimator);
                picked.add(c);
                used += c.tokens + PER_SNIPPET_OVERHEAD_TOKENS;
            } else {
                omitted++;
            }
        }

        List<SearchHit> hits = new ArrayList<>(picked.size());
        Set<String> sourceFiles = new HashSet<>();
        long sourceFileTokens = 0;
        for (Candidate c : picked) {
            WorkspaceRecord ws = workspaces.get(c.wsIdx);
            if (sourceFiles.add(c.wsIdx + "\u0000" + c.path)) {
                FileState fs = indexes.get(c.wsIdx).state(c.path);
                if (fs != null) {
                    sourceFileTokens += fs.fileTokens();
                }
            }
            hits.add(new SearchHit(ws.workspaceId(), c.path, ws.root().resolve(c.path).toString(), c.language,
                    c.symbol, c.kind, c.startLine, c.endLine, Math.round(c.score * 100.0) / 100.0, c.content,
                    c.tokens));
        }

        SearchResultFormatter.IndexInfo info = indexInfo(workspaces);
        String text = SearchResultFormatter.format(request.query(), hits, omitted, maxTokens, info);
        int returnedTokens = estimator.estimate(text);
        SearchTotals totals = new SearchTotals(returnedTokens, sourceFileTokens, sourceFiles.size(), hits.size(),
                omitted);
        long tookMs = (System.nanoTime() - started) / 1_000_000L;
        SearchResponse response = new SearchResponse(UUID.randomUUID().toString(), request.query(), tookMs,
                info.fresh() ? "fresh" : "stale", hits, totals, text);
        List<String> ids = workspaces.stream().map(WorkspaceRecord::workspaceId).toList();
        return new Outcome(response, ids);
    }

    private SearchResultFormatter.IndexInfo indexInfo(List<WorkspaceRecord> workspaces) {
        String state = "ready";
        int files = 0;
        int done = 0;
        int total = 0;
        List<String> roots = new ArrayList<>();
        for (WorkspaceRecord ws : workspaces) {
            IndexStatus s = indexer.status(ws);
            if (!"ready".equals(s.state()) && "ready".equals(state)) {
                state = s.state();
            }
            files += s.filesIndexed();
            done += s.progress().done();
            total += s.progress().total();
            roots.add(s.root());
        }
        return new SearchResultFormatter.IndexInfo(state, files, done, total, roots);
    }

    private void ensureFresh(WorkspaceRecord ws, WorkspaceIndex index, boolean wasOpen) {
        try {
            if (indexer.isBusy(ws.workspaceId())) {
                return;
            }
            long interval = props.reconcileIntervalMinutes() * 60_000L;
            long last = index.lastFullScanAt();
            if (!wasOpen || last == 0 || System.currentTimeMillis() - last > interval) {
                indexer.requestReconcile(ws.workspaceId());
            }
        } catch (RuntimeException e) {
            log.debug("VatStash could not schedule a reconcile for {}: {}", ws.workspaceId(), e.getMessage());
        }
    }

    private List<WorkspaceRecord> resolve(SearchRequest request) {
        Set<String> seen = new LinkedHashSet<>();
        List<WorkspaceRecord> out = new ArrayList<>();
        boolean hasIds = request.workspaceIds() != null && !request.workspaceIds().isEmpty();
        boolean hasRoot = request.root() != null && !request.root().isBlank();
        if (!hasIds && !hasRoot) {
            throw VatStashException.invalidRequest("either workspaceIds or root is required");
        }
        if (hasIds) {
            for (String id : request.workspaceIds()) {
                WorkspaceRecord ws = registry.find(id)
                        .orElseThrow(() -> VatStashException.workspaceNotFound("workspace " + id));
                if (seen.add(ws.workspaceId())) {
                    out.add(ws);
                }
            }
        } else {
            WorkspaceRecord ws = registry.findByPath(request.root())
                    .orElseThrow(() -> VatStashException.workspaceNotFound("root " + request.root()));
            out.add(ws);
        }
        return out;
    }

    private static int workspaceIndexOf(int[] starts, int docId) {
        int idx = 0;
        for (int i = 0; i < starts.length; i++) {
            if (docId >= starts[i]) {
                idx = i;
            }
        }
        return idx;
    }

    private static int clamp(Integer value, int def, int min, int max) {
        int v = value == null ? def : value;
        return Math.max(min, Math.min(max, v));
    }

    /** A selected chunk that can absorb overlapping chunks of the same file. */
    private static final class Candidate {
        final int wsIdx;
        final String path;
        String language;
        String symbol;
        String kind;
        int startLine;
        int endLine;
        String content;
        int tokens;
        float score;

        Candidate(int wsIdx, ScoredChunk sc) {
            this.wsIdx = wsIdx;
            this.path = sc.path();
            this.language = sc.language();
            this.symbol = sc.symbol();
            this.kind = sc.kind();
            this.startLine = sc.startLine();
            this.endLine = sc.endLine();
            this.content = sc.content();
            this.tokens = sc.tokens();
            this.score = sc.score();
        }

        private boolean contiguous() {
            return !"type".equals(kind) && !"members".equals(kind);
        }

        boolean tryMerge(Candidate other, JtokkitEstimator estimator) {
            if (!contiguous() || !other.contiguous()) {
                return false;
            }
            if (other.startLine > endLine || other.endLine < startLine) {
                return false;
            }
            String[] mine = content.split("\n", -1);
            String[] theirs = other.content.split("\n", -1);
            if (mine.length != endLine - startLine + 1 || theirs.length != other.endLine - other.startLine + 1) {
                return false;
            }
            boolean otherWins = other.score > score;
            if (other.startLine >= startLine && other.endLine <= endLine) {
                // fully inside: keep this text
            } else if (startLine >= other.startLine && endLine <= other.endLine) {
                content = other.content;
                startLine = other.startLine;
                endLine = other.endLine;
            } else if (startLine <= other.startLine) {
                int skip = endLine - other.startLine + 1;
                StringBuilder sb = new StringBuilder(content);
                for (int i = skip; i < theirs.length; i++) {
                    sb.append('\n').append(theirs[i]);
                }
                content = sb.toString();
                endLine = other.endLine;
            } else {
                int skip = other.endLine - startLine + 1;
                StringBuilder sb = new StringBuilder(other.content);
                for (int i = skip; i < mine.length; i++) {
                    sb.append('\n').append(mine[i]);
                }
                content = sb.toString();
                startLine = other.startLine;
            }
            if (otherWins) {
                score = other.score;
                symbol = other.symbol;
                kind = other.kind;
            }
            tokens = estimator.estimate(content);
            return true;
        }

        void truncateTo(int budgetTokens, JtokkitEstimator estimator) {
            if (tokens <= budgetTokens) {
                return;
            }
            String[] lines = content.split("\n", -1);
            int lo = 1;
            int hi = lines.length;
            int best = 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                String candidate = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, mid));
                if (estimator.estimate(candidate) <= budgetTokens) {
                    best = mid;
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            String cut = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, best));
            if (best == 1 && estimator.estimate(cut) > budgetTokens) {
                cut = cut.substring(0, Math.min(cut.length(), Math.max(1, budgetTokens * 3)));
            }
            content = cut + "\n" + TRUNCATED_LINE;
            endLine = Math.min(endLine, startLine + best - 1);
            tokens = estimator.estimate(content);
        }
    }
}
