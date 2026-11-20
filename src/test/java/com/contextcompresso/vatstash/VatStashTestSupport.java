package com.contextcompresso.vatstash;

import com.contextcompresso.config.VatStashProperties;
import com.contextcompresso.tokenizer.JtokkitEstimator;
import com.contextcompresso.vatstash.chunk.ChunkerRegistry;
import com.contextcompresso.vatstash.chunk.LanguageDetector;
import com.contextcompresso.vatstash.index.IndexStatus;
import com.contextcompresso.vatstash.metrics.SearchEventStore;
import com.contextcompresso.vatstash.metrics.VatStashMetricsService;
import com.contextcompresso.vatstash.scan.WorkspaceScanner;
import com.contextcompresso.vatstash.search.Bm25Retriever;
import com.contextcompresso.vatstash.search.CodeSearchService;
import com.contextcompresso.vatstash.search.QueryBuilder;
import com.contextcompresso.vatstash.workspace.WorkspaceIndexer;
import com.contextcompresso.vatstash.workspace.WorkspaceRecord;
import com.contextcompresso.vatstash.workspace.WorkspaceRegistry;
import com.contextcompresso.vatstash.workspace.WorkspaceSettings;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.JDBC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.UnaryOperator;

/** Wires the VatStash beans by hand for unit tests: real SQLite, real Lucene, no Spring context. */
public final class VatStashTestSupport {

    private VatStashTestSupport() {
    }

    /** Small commit interval and no pause between files, so tests are quick. */
    public static VatStashProperties fastProps(Path indexDir) {
        return new VatStashProperties(true, indexDir.toString(), 524288, 50000, 20, 15, 120, 10, 6000,
                50L, 0L, 15, 8, 30, 4000, 16000, 3, 90, false, true, true);
    }

    public static VatStashProperties withIndexingPause(VatStashProperties p, long pauseMs) {
        return new VatStashProperties(p.enabled(), p.indexDir(), p.maxFileBytes(), p.maxFilesPerWorkspace(),
                p.maxOpenWorkspaces(), p.minChunkLines(), p.maxChunkLines(), p.overlapLines(), p.maxChunkChars(),
                p.commitIntervalMs(), pauseMs, p.reconcileIntervalMinutes(), p.defaultMaxResults(),
                p.maxResultsCap(), p.defaultMaxTokens(), p.maxTokensCap(), p.maxChunksPerFile(),
                p.metricsRetentionDays(), p.storeQueryText(), p.mcpEnabled(), p.mcpAutoRegister());
    }

    public static VatStashProperties withStoreQueryText(VatStashProperties p, boolean store) {
        return new VatStashProperties(p.enabled(), p.indexDir(), p.maxFileBytes(), p.maxFilesPerWorkspace(),
                p.maxOpenWorkspaces(), p.minChunkLines(), p.maxChunkLines(), p.overlapLines(), p.maxChunkChars(),
                p.commitIntervalMs(), p.indexingPauseMs(), p.reconcileIntervalMinutes(), p.defaultMaxResults(),
                p.maxResultsCap(), p.defaultMaxTokens(), p.maxTokensCap(), p.maxChunksPerFile(),
                p.metricsRetentionDays(), store, p.mcpEnabled(), p.mcpAutoRegister());
    }

    public static Env env(Path tmp) throws IOException {
        return env(tmp, UnaryOperator.identity());
    }

    public static Env env(Path tmp, UnaryOperator<VatStashProperties> tweak) throws IOException {
        VatStashProperties props = tweak.apply(fastProps(tmp.resolve("vatstash-index")));
        Path dbFile = tmp.resolve("ccr.db");
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        dataSource.setDriverClass(JDBC.class);
        dataSource.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        WorkspaceRegistry registry = new WorkspaceRegistry(jdbc, props, new ObjectMapper(), dbFile.toString());
        WorkspaceScanner scanner = new WorkspaceScanner(new LanguageDetector());
        ChunkerRegistry chunkers = new ChunkerRegistry(props);
        JtokkitEstimator estimator = new JtokkitEstimator();
        WorkspaceIndexer indexer = new WorkspaceIndexer(registry, scanner, chunkers, estimator, props);
        CodeSearchService search = new CodeSearchService(registry, indexer, new QueryBuilder(),
                new Bm25Retriever(), estimator, props);
        SearchEventStore store = new SearchEventStore(jdbc);
        VatStashMetricsService metrics = new VatStashMetricsService(store, props);
        VatStashService service = new VatStashService(props, registry, indexer, search, metrics);
        service.init();
        return new Env(props, jdbc, registry, indexer, search, store, metrics, service);
    }

    public record Env(VatStashProperties props, JdbcTemplate jdbc, WorkspaceRegistry registry,
                      WorkspaceIndexer indexer, CodeSearchService search, SearchEventStore store,
                      VatStashMetricsService metrics, VatStashService service) implements AutoCloseable {

        /** Registers the directory, starts the first scan and waits until the index is ready. */
        public WorkspaceRecord registerAndIndex(Path root) {
            WorkspaceRecord ws = registry.register(root.toString(), WorkspaceSettings.empty());
            registry.openIndex(ws.workspaceId());
            indexer.requestReconcile(ws.workspaceId());
            awaitReady(ws);
            return ws;
        }

        public IndexStatus awaitReady(WorkspaceRecord ws) {
            if (!indexer.awaitIdle(ws.workspaceId(), Duration.ofSeconds(30))) {
                throw new AssertionError("indexer did not become idle for " + ws.workspaceId());
            }
            IndexStatus status = indexer.status(ws);
            if (!"ready".equals(status.state())) {
                throw new AssertionError("expected ready but was " + status);
            }
            return status;
        }

        @Override
        public void close() {
            indexer.close();
            registry.closeAll();
            service.shutdown();
        }
    }

    public static Path write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
        return p;
    }

    /** Sets the mtime explicitly, so a rewrite with the same size still looks changed or unchanged on demand. */
    public static void setMtime(Path file, long epochMs) throws IOException {
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(epochMs));
    }
}
