package com.contextcompresso.vatstash.workspace;

import com.contextcompresso.config.VatStashProperties;
import com.contextcompresso.tokenizer.JtokkitEstimator;
import com.contextcompresso.tokenizer.TokenEstimator;
import com.contextcompresso.vatstash.VatStashException;
import com.contextcompresso.vatstash.chunk.ChunkerRegistry;
import com.contextcompresso.vatstash.chunk.CodeChunk;
import com.contextcompresso.vatstash.index.FileState;
import com.contextcompresso.vatstash.index.IndexStatus;
import com.contextcompresso.vatstash.index.TokenizedChunk;
import com.contextcompresso.vatstash.index.WorkspaceIndex;
import com.contextcompresso.vatstash.scan.IgnoreRules;
import com.contextcompresso.vatstash.scan.PathGuard;
import com.contextcompresso.vatstash.scan.ScanResult;
import com.contextcompresso.vatstash.scan.ScannedFile;
import com.contextcompresso.vatstash.scan.WorkspaceScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the Lucene indexes up to date. One low-priority daemon thread serves every workspace,
 * one unit of work at a time, so a large first scan of one workspace does not starve the others.
 */
@Component
public class WorkspaceIndexer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceIndexer.class);

    /** Above this many paths in a batch a reconcile is cheaper than handling them one by one. */
    public static final int CHANGE_BATCH_LIMIT = 2000;
    private static final int REFRESH_EVERY_FILES = 50;
    private static final long REFRESH_EVERY_MS = 1000;

    private final WorkspaceRegistry registry;
    private final WorkspaceScanner scanner;
    private final ChunkerRegistry chunkers;
    private final TokenEstimator estimator;
    private final VatStashProperties props;
    private final ExecutorService executor;
    private final Map<String, WsState> states = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private final AtomicLong filesRead = new AtomicLong();
    private final AtomicLong filesChunked = new AtomicLong();
    private final AtomicLong manifestTouches = new AtomicLong();
    private final AtomicLong reconciles = new AtomicLong();

    public WorkspaceIndexer(WorkspaceRegistry registry, WorkspaceScanner scanner, ChunkerRegistry chunkers,
                            JtokkitEstimator estimator, VatStashProperties props) {
        this.registry = registry;
        this.scanner = scanner;
        this.chunkers = chunkers;
        this.estimator = estimator;
        this.props = props;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "vatstash-indexer");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    // ---- public API ----

    public record ChangeResult(int accepted, int ignored) {
    }

    /** Schedules a reconcile (scan, compare to the manifest, apply the differences). Coalesces. */
    public void requestReconcile(String workspaceId) {
        WsState st = state(workspaceId);
        synchronized (st) {
            st.reconcileRequested = true;
            submitLocked(st);
        }
    }

    /** Schedules a rebuild that drops the index first. */
    public void requestFullRebuild(String workspaceId) {
        WsState st = state(workspaceId);
        synchronized (st) {
            st.fullRequested = true;
            submitLocked(st);
        }
    }

    /** Queues changed and deleted absolute paths. Paths outside the root or ignored count as ignored. */
    public ChangeResult enqueueChanges(WorkspaceRecord ws, List<String> changed, List<String> deleted) {
        List<String> c = changed == null ? List.of() : changed;
        List<String> d = deleted == null ? List.of() : deleted;
        WsState st = state(ws.workspaceId());
        if (c.size() + d.size() > CHANGE_BATCH_LIMIT) {
            requestReconcile(ws.workspaceId());
            return new ChangeResult(c.size() + d.size(), 0);
        }
        IgnoreRules rules = rulesFor(st, ws, false);
        int accepted = 0;
        int ignored = 0;
        boolean needReconcile = false;
        Set<String> changedRel = new LinkedHashSet<>();
        Set<String> deletedRel = new LinkedHashSet<>();
        for (String abs : c) {
            Optional<String> rel = PathGuard.relativize(ws.root(), abs);
            if (rel.isEmpty()) {
                ignored++;
                continue;
            }
            String r = rel.get();
            if (isIgnoreFile(r)) {
                needReconcile = true;
                accepted++;
                continue;
            }
            if (Files.isDirectory(ws.root().resolve(r), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                needReconcile = true;
                accepted++;
                continue;
            }
            if (rules.isIgnored(r, false)) {
                ignored++;
                continue;
            }
            changedRel.add(r);
            accepted++;
        }
        for (String abs : d) {
            Optional<String> rel = PathGuard.relativize(ws.root(), abs);
            if (rel.isEmpty()) {
                ignored++;
                continue;
            }
            String r = rel.get();
            if (isIgnoreFile(r)) {
                needReconcile = true;
            }
            deletedRel.add(r);
            accepted++;
        }
        synchronized (st) {
            for (String r : deletedRel) {
                st.pendingChanged.remove(r);
                st.pendingDeleted.add(r);
            }
            for (String r : changedRel) {
                st.pendingDeleted.remove(r);
                st.pendingChanged.add(r);
            }
            if (needReconcile || st.pendingChanged.size() + st.pendingDeleted.size() > CHANGE_BATCH_LIMIT) {
                st.reconcileRequested = true;
            }
            if (st.reconcileRequested || !st.pendingChanged.isEmpty() || !st.pendingDeleted.isEmpty()) {
                submitLocked(st);
            }
        }
        return new ChangeResult(accepted, ignored);
    }

    /** Builds the wire status. Opens the index if needed; an index that cannot open reports state error. */
    public IndexStatus status(WorkspaceRecord ws) {
        WsState st = state(ws.workspaceId());
        WorkspaceIndex index;
        try {
            index = registry.openIndex(ws.workspaceId());
        } catch (VatStashException e) {
            return new IndexStatus(ws.workspaceId(), ws.root().toString(), "error", "stale", 0, 0, 0, 0,
                    new IndexStatus.Progress(0, 0), 0, 0, 0, false, e.getMessage());
        }
        int pending;
        boolean busy;
        int done;
        int total;
        synchronized (st) {
            pending = st.pendingChanged.size() + st.pendingDeleted.size();
            // A reconcile of a built index is verification. It only counts as work once it has
            // found something to replace or delete (modified). Queued change events, a queued
            // rebuild and a running change batch or rebuild always count.
            Kind running = st.runKind;
            busy = pending > 0 || st.fullRequested || st.modified
                    || (st.running && running != null && running != Kind.RECONCILE);
            done = st.done;
            total = st.total;
        }
        if (st.running && st.total > st.done) {
            pending += st.total - st.done;
        }
        long lastFull = index.lastFullScanAt();
        String state;
        if (st.lastError != null && !busy) {
            state = "error";
        } else if (lastFull == 0) {
            state = "building";
        } else if (busy) {
            state = "updating";
        } else {
            state = "ready";
        }
        String freshness = "ready".equals(state) ? "fresh" : "stale";
        return new IndexStatus(ws.workspaceId(), ws.root().toString(), state, freshness,
                index.filesIndexed(), st.skipped, index.chunksIndexed(), pending,
                new IndexStatus.Progress(done, total), index.indexBytes(), lastFull,
                Math.max(st.lastUpdateAt, lastFull), st.truncated, st.lastError);
    }

    public boolean isBusy(String workspaceId) {
        WsState st = states.get(workspaceId);
        if (st == null) {
            return false;
        }
        synchronized (st) {
            return st.scheduled;
        }
    }

    /** Test hook: waits until the workspace has no queued or running work. */
    public boolean awaitIdle(String workspaceId, Duration timeout) {
        WsState st = states.get(workspaceId);
        if (st == null) {
            return true;
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (st) {
            while (st.scheduled) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(st, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** Cancels pending work for a workspace that is being removed and drops its state. */
    public void forget(String workspaceId) {
        WsState st = states.get(workspaceId);
        if (st == null) {
            return;
        }
        st.cancel = true;
        synchronized (st) {
            st.pendingChanged.clear();
            st.pendingDeleted.clear();
            st.reconcileRequested = false;
            st.fullRequested = false;
        }
        awaitIdle(workspaceId, Duration.ofSeconds(10));
        states.remove(workspaceId);
    }

    /** Commits indexes whose commit interval has elapsed. */
    public void flushOpenIndexes() {
        for (String id : registry.openIds()) {
            try {
                if (registry.isOpen(id)) {
                    registry.openIndex(id).maybeCommit();
                }
            } catch (IOException | RuntimeException e) {
                log.debug("VatStash commit for {} failed: {}", id, e.getMessage());
            }
        }
    }

    public long filesRead() {
        return filesRead.get();
    }

    public long filesChunked() {
        return filesChunked.get();
    }

    public long manifestTouches() {
        return manifestTouches.get();
    }

    public long reconcileCount() {
        return reconciles.get();
    }

    @Override
    public void close() {
        closed = true;
        states.values().forEach(s -> s.cancel = true);
        executor.shutdownNow();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- scheduling ----

    private WsState state(String id) {
        return states.computeIfAbsent(id, WsState::new);
    }

    private void submitLocked(WsState st) {
        if (st.scheduled || closed) {
            return;
        }
        st.scheduled = true;
        try {
            executor.execute(() -> runOnce(st));
        } catch (RejectedExecutionException e) {
            st.scheduled = false;
            st.notifyAll();
        }
    }

    private enum Kind { FULL, RECONCILE, CHANGES }

    private record Work(Kind kind, Set<String> changed, Set<String> deleted) {
    }

    private void runOnce(WsState st) {
        Work work;
        synchronized (st) {
            work = takeWorkLocked(st);
            if (work == null || closed) {
                st.scheduled = false;
                st.notifyAll();
                return;
            }
            st.running = true;
            st.runKind = work.kind();
        }
        try {
            execute(st, work);
            st.lastError = null;
        } catch (Throwable t) {
            st.lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            log.warn("VatStash indexing of workspace {} failed: {}", st.id, st.lastError, t);
        } finally {
            synchronized (st) {
                st.running = false;
                st.runKind = null;
                st.modified = false;
                boolean more = !closed && !st.cancel && hasWorkLocked(st);
                if (more) {
                    try {
                        executor.execute(() -> runOnce(st));
                    } catch (RejectedExecutionException e) {
                        st.scheduled = false;
                        st.notifyAll();
                    }
                } else {
                    st.scheduled = false;
                    st.notifyAll();
                }
            }
        }
    }

    private static boolean hasWorkLocked(WsState st) {
        return st.fullRequested || st.reconcileRequested || !st.pendingChanged.isEmpty()
                || !st.pendingDeleted.isEmpty();
    }

    private static Work takeWorkLocked(WsState st) {
        if (st.cancel) {
            return null;
        }
        if (st.fullRequested) {
            st.fullRequested = false;
            st.reconcileRequested = false;
            st.pendingChanged.clear();
            st.pendingDeleted.clear();
            return new Work(Kind.FULL, Set.of(), Set.of());
        }
        if (st.reconcileRequested) {
            st.reconcileRequested = false;
            st.pendingChanged.clear();
            st.pendingDeleted.clear();
            return new Work(Kind.RECONCILE, Set.of(), Set.of());
        }
        if (!st.pendingChanged.isEmpty() || !st.pendingDeleted.isEmpty()) {
            Work w = new Work(Kind.CHANGES, new LinkedHashSet<>(st.pendingChanged),
                    new LinkedHashSet<>(st.pendingDeleted));
            st.pendingChanged.clear();
            st.pendingDeleted.clear();
            return w;
        }
        return null;
    }

    // ---- work ----

    private void execute(WsState st, Work work) throws IOException {
        Optional<WorkspaceRecord> found = registry.find(st.id);
        if (found.isEmpty()) {
            return;
        }
        WorkspaceRecord ws = found.get();
        WorkspaceIndex index = registry.openIndex(st.id);
        index.pin();
        try {
            switch (work.kind()) {
                case FULL -> {
                    index.deleteAll();
                    reconcile(st, ws, index);
                }
                case RECONCILE -> reconcile(st, ws, index);
                case CHANGES -> applyChanges(st, ws, index, work);
            }
        } finally {
            index.unpin();
        }
    }

    private void reconcile(WsState st, WorkspaceRecord ws, WorkspaceIndex index) throws IOException {
        reconciles.incrementAndGet();
        IgnoreRules rules = rulesFor(st, ws, true);
        long maxBytes = ws.settings().maxFileBytes() != null ? ws.settings().maxFileBytes() : props.maxFileBytes();
        int maxFiles = ws.settings().maxFiles() != null ? ws.settings().maxFiles() : props.maxFilesPerWorkspace();
        ScanResult scan = scanner.scan(ws.root(), rules, maxBytes, maxFiles);
        st.truncated = scan.truncated();
        st.skipped = scan.skipped();
        st.total = scan.files().size();
        st.done = 0;

        long lastRefresh = System.currentTimeMillis();
        int sinceRefresh = 0;
        Set<String> present = new HashSet<>(scan.files().size() * 2);
        for (ScannedFile f : scan.files()) {
            if (st.cancel || closed) {
                return;
            }
            present.add(f.rel());
            boolean worked;
            try {
                worked = processFile(st, index, f);
            } catch (IOException | RuntimeException e) {
                log.debug("VatStash skipped {}: {}", f.rel(), e.getMessage());
                worked = false;
            }
            st.done++;
            sinceRefresh++;
            long now = System.currentTimeMillis();
            if (sinceRefresh >= REFRESH_EVERY_FILES || now - lastRefresh >= REFRESH_EVERY_MS) {
                index.refresh();
                index.maybeCommit();
                sinceRefresh = 0;
                lastRefresh = now;
            }
            if (worked) {
                pause();
            }
        }
        for (String key : new ArrayList<>(index.manifestKeys())) {
            if (!present.contains(key)) {
                st.modified = true;
                index.deleteFile(key);
            }
        }
        long now = System.currentTimeMillis();
        index.setLastFullScanAt(now);
        index.commit();
        index.refresh();
        st.lastUpdateAt = now;
        registry.touch(ws.workspaceId());
    }

    private void applyChanges(WsState st, WorkspaceRecord ws, WorkspaceIndex index, Work work) throws IOException {
        IgnoreRules rules = rulesFor(st, ws, true);
        long maxBytes = ws.settings().maxFileBytes() != null ? ws.settings().maxFileBytes() : props.maxFileBytes();
        st.total = work.changed().size() + work.deleted().size();
        st.done = 0;
        for (String rel : work.deleted()) {
            if (st.cancel || closed) {
                return;
            }
            st.modified = true;
            index.deletePrefix(rel);
            st.done++;
        }
        for (String rel : work.changed()) {
            if (st.cancel || closed) {
                return;
            }
            Optional<ScannedFile> file = scanner.inspect(ws.root(), rel, rules, maxBytes);
            try {
                if (file.isPresent()) {
                    processFile(st, index, file.get());
                } else if (index.state(rel) != null) {
                    st.modified = true;
                    index.deleteFile(rel);
                }
            } catch (IOException | RuntimeException e) {
                log.debug("VatStash skipped {}: {}", rel, e.getMessage());
            }
            st.done++;
        }
        index.refresh();
        index.maybeCommit();
        st.lastUpdateAt = System.currentTimeMillis();
    }

    /** Returns true when the file was read (as opposed to skipped on unchanged stat values). */
    private boolean processFile(WsState st, WorkspaceIndex index, ScannedFile f) throws IOException {
        FileState old = index.state(f.rel());
        if (old != null && old.mtime() == f.mtimeMs() && old.size() == f.size()) {
            return false;
        }
        byte[] bytes = Files.readAllBytes(f.abs());
        filesRead.incrementAndGet();
        String sha = sha256(bytes);
        if (old != null && sha.equals(old.sha256())) {
            index.touchManifest(f.rel(), old.withStat(f.mtimeMs(), f.size()));
            manifestTouches.incrementAndGet();
            return true;
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        List<CodeChunk> chunks = chunkers.chunk(f.rel(), f.language(), content);
        List<TokenizedChunk> tokenized = new ArrayList<>(chunks.size());
        for (CodeChunk c : chunks) {
            tokenized.add(new TokenizedChunk(c, estimator.estimate(c.content())));
        }
        FileState state = new FileState(sha, f.mtimeMs(), f.size(), estimator.estimate(content), chunks.size(),
                null, System.currentTimeMillis());
        st.modified = true;
        index.replaceFile(f.rel(), tokenized, state);
        filesChunked.incrementAndGet();
        return true;
    }

    private void pause() {
        long ms = props.indexingPauseMs();
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private IgnoreRules rulesFor(WsState st, WorkspaceRecord ws, boolean reload) {
        synchronized (st) {
            if (st.rules == null || !ws.settings().equals(st.rulesSettings) || !ws.root().equals(st.rulesRoot)) {
                st.rules = new IgnoreRules(ws.root(), ws.settings().excludeGlobs());
                st.rulesSettings = ws.settings();
                st.rulesRoot = ws.root();
            } else if (reload) {
                st.rules.reload();
            }
            return st.rules;
        }
    }

    private static boolean isIgnoreFile(String rel) {
        return rel.equals(".gitignore") || rel.endsWith("/.gitignore")
                || rel.equals(".vatstashignore") || rel.endsWith("/.vatstashignore");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static final class WsState {
        final String id;
        final Set<String> pendingChanged = new LinkedHashSet<>();
        final Set<String> pendingDeleted = new LinkedHashSet<>();
        boolean reconcileRequested;
        boolean fullRequested;
        boolean scheduled;
        volatile boolean running;
        volatile Kind runKind;
        /** Set once the current run replaces or deletes an indexed file; cleared when the run ends. */
        volatile boolean modified;
        volatile boolean cancel;
        volatile int done;
        volatile int total;
        volatile int skipped;
        volatile boolean truncated;
        volatile long lastUpdateAt;
        volatile String lastError;
        IgnoreRules rules;
        WorkspaceSettings rulesSettings;
        Path rulesRoot;

        WsState(String id) {
            this.id = id;
        }
    }
}
