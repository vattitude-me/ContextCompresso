package com.contextcompresso.vatstash;

import com.contextcompresso.config.VatStashProperties;
import com.contextcompresso.vatstash.index.IndexStatus;
import com.contextcompresso.vatstash.metrics.VatStashMetricsService;
import com.contextcompresso.vatstash.search.CodeSearchService;
import com.contextcompresso.vatstash.search.SearchRequest;
import com.contextcompresso.vatstash.search.SearchResponse;
import com.contextcompresso.vatstash.workspace.WorkspaceIndexer;
import com.contextcompresso.vatstash.workspace.WorkspaceInfo;
import com.contextcompresso.vatstash.workspace.WorkspaceRecord;
import com.contextcompresso.vatstash.workspace.WorkspaceRegistry;
import com.contextcompresso.vatstash.workspace.WorkspaceSettings;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * Facade over the whole VatStash feature. Nothing here may disturb the proxy routes, so the
 * constructor never throws; an initialisation problem leaves the feature unavailable (503).
 */
@Service
public class VatStashService {

    private static final Logger log = LoggerFactory.getLogger(VatStashService.class);
    private static final long SEEN_WINDOW_MS = 24L * 60 * 60 * 1000;

    private final VatStashProperties props;
    private final WorkspaceRegistry registry;
    private final WorkspaceIndexer indexer;
    private final CodeSearchService searchService;
    private final VatStashMetricsService metrics;
    private final ExecutorService metricsExecutor;
    private volatile boolean available = true;

    public VatStashService(VatStashProperties props, WorkspaceRegistry registry, WorkspaceIndexer indexer,
                           CodeSearchService searchService, VatStashMetricsService metrics) {
        this.props = props;
        this.registry = registry;
        this.indexer = indexer;
        this.searchService = searchService;
        this.metrics = metrics;
        this.metricsExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vatstash-metrics");
            t.setDaemon(true);
            return t;
        });
    }

    @PostConstruct
    void init() {
        if (!props.enabled()) {
            log.info("VatStash is disabled (contextcompresso.vatstash.enabled=false)");
            return;
        }
        try {
            Files.createDirectories(registry.baseDir());
            log.info("VatStash index directory: {}", registry.baseDir());
        } catch (IOException | RuntimeException e) {
            available = false;
            log.warn("VatStash is unavailable, cannot create {}: {}", registry.baseDir(), e.getMessage());
        }
    }

    public boolean isEnabled() {
        return props.enabled();
    }

    public boolean isAvailable() {
        return props.enabled() && available;
    }

    private void requireAvailable() {
        if (!props.enabled()) {
            throw VatStashException.disabled();
        }
        if (!available) {
            throw VatStashException.unavailable("VatStash could not initialise its index directory", null);
        }
    }

    public WorkspaceInfo register(String root, WorkspaceSettings settings) {
        requireAvailable();
        WorkspaceRecord ws = registry.register(root, settings);
        registry.openIndex(ws.workspaceId());
        indexer.requestReconcile(ws.workspaceId());
        return info(ws);
    }

    public List<WorkspaceInfo> list() {
        requireAvailable();
        List<WorkspaceInfo> out = new ArrayList<>();
        for (WorkspaceRecord ws : registry.list()) {
            out.add(info(ws));
        }
        return out;
    }

    public IndexStatus status(String id) {
        requireAvailable();
        return indexer.status(require(id));
    }

    public WorkspaceIndexer.ChangeResult applyChanges(String id, List<String> changed, List<String> deleted) {
        requireAvailable();
        int total = (changed == null ? 0 : changed.size()) + (deleted == null ? 0 : deleted.size());
        if (total > WorkspaceIndexer.CHANGE_BATCH_LIMIT) {
            throw VatStashException.invalidRequest(
                    "at most " + WorkspaceIndexer.CHANGE_BATCH_LIMIT + " paths per request; use reindex instead");
        }
        WorkspaceRecord ws = require(id);
        registry.openIndex(id);
        registry.touch(id);
        return indexer.enqueueChanges(ws, changed, deleted);
    }

    public IndexStatus reindex(String id, boolean full) {
        requireAvailable();
        WorkspaceRecord ws = require(id);
        registry.openIndex(id);
        if (full) {
            indexer.requestFullRebuild(id);
        } else {
            indexer.requestReconcile(id);
        }
        return indexer.status(ws);
    }

    public void remove(String id) {
        requireAvailable();
        require(id);
        indexer.forget(id);
        registry.remove(id);
    }

    public SearchResponse search(SearchRequest request) {
        requireAvailable();
        CodeSearchService.Outcome outcome = searchService.searchWithContext(request);
        try {
            metricsExecutor.execute(() -> metrics.record(request, outcome.response(), outcome.workspaceIds()));
        } catch (RejectedExecutionException e) {
            log.debug("VatStash metrics executor is shut down");
        }
        return outcome.response();
    }

    public Optional<WorkspaceRecord> resolveByPath(String path) {
        requireAvailable();
        return registry.findByPath(path);
    }

    public Optional<WorkspaceRecord> findWorkspace(String id) {
        requireAvailable();
        return registry.find(id);
    }

    public List<WorkspaceRecord> registeredWorkspaces() {
        requireAvailable();
        return registry.list();
    }

    private WorkspaceRecord require(String id) {
        return registry.find(id).orElseThrow(() -> VatStashException.workspaceNotFound("workspace " + id));
    }

    private WorkspaceInfo info(WorkspaceRecord ws) {
        return new WorkspaceInfo(ws.workspaceId(), ws.root().toString(), indexer.status(ws));
    }

    /** Re-checks open workspaces seen in the last 24 hours and commits quiet indexes. Never blocks. */
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void tick() {
        if (!isAvailable()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            long interval = props.reconcileIntervalMinutes() * 60_000L;
            for (WorkspaceRecord ws : registry.list()) {
                if (now - ws.lastSeenAt() > SEEN_WINDOW_MS || !registry.isOpen(ws.workspaceId())
                        || indexer.isBusy(ws.workspaceId())) {
                    continue;
                }
                long last = registry.openIndex(ws.workspaceId()).lastFullScanAt();
                if (now - last > interval) {
                    indexer.requestReconcile(ws.workspaceId());
                }
            }
            indexer.flushOpenIndexes();
        } catch (RuntimeException e) {
            log.warn("VatStash maintenance tick failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            indexer.close();
            registry.closeAll();
        } catch (RuntimeException e) {
            log.warn("VatStash shutdown failed: {}", e.getMessage());
        } finally {
            metricsExecutor.shutdown();
        }
    }
}
