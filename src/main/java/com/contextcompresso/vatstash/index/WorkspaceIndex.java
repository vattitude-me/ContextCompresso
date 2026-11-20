package com.contextcompresso.vatstash.index;

import com.contextcompresso.config.VatStashProperties;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One Lucene index per workspace. Every file is stored as one "file" manifest document (sha256,
 * mtime, size, token count) plus its chunk documents, all sharing {@code file_key}, so replacing a
 * file is a single atomic {@code updateDocuments} and the manifest cannot drift from the chunks.
 */
public class WorkspaceIndex implements Closeable {

    private static final String COMMIT_LAST_FULL_SCAN = "lastFullScanAt";

    private final Path dir;
    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    private final long commitIntervalMs;
    private final Map<String, FileState> manifest = new ConcurrentHashMap<>();
    private final AtomicInteger pins = new AtomicInteger();

    private volatile boolean dirty;
    private volatile boolean closed;
    private volatile long lastCommitMs = System.currentTimeMillis();
    private volatile long lastFullScanAt;

    private WorkspaceIndex(Path dir, Directory directory, IndexWriter writer, SearcherManager sm,
                           long commitIntervalMs) {
        this.dir = dir;
        this.directory = directory;
        this.writer = writer;
        this.searcherManager = sm;
        this.commitIntervalMs = commitIntervalMs;
    }

    /** Opens (or creates) the index under {@code workspaceDir/lucene}. */
    public static WorkspaceIndex open(Path workspaceDir, VatStashProperties props) throws IOException {
        Path luceneDir = workspaceDir.resolve("lucene");
        Files.createDirectories(luceneDir);
        Directory directory = FSDirectory.open(luceneDir);
        IndexWriter writer = null;
        try {
            IndexWriterConfig cfg = new IndexWriterConfig(new CodeAnalyzer());
            cfg.setSimilarity(new BM25Similarity());
            cfg.setRAMBufferSizeMB(32);
            cfg.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            writer = new IndexWriter(directory, cfg);
            SearcherManager sm = new SearcherManager(writer, true, false, null);
            WorkspaceIndex index = new WorkspaceIndex(workspaceDir, directory, writer, sm, props.commitIntervalMs());
            index.loadManifest();
            return index;
        } catch (IOException | RuntimeException e) {
            try {
                if (writer != null) {
                    writer.close();
                }
            } catch (IOException ignored) {
                // best effort
            }
            directory.close();
            throw e;
        }
    }

    private void loadManifest() throws IOException {
        for (Map.Entry<String, String> e : writer.getLiveCommitData() == null
                ? List.<Map.Entry<String, String>>of() : toList(writer.getLiveCommitData())) {
            if (COMMIT_LAST_FULL_SCAN.equals(e.getKey())) {
                try {
                    lastFullScanAt = Long.parseLong(e.getValue());
                } catch (NumberFormatException ignored) {
                    lastFullScanAt = 0;
                }
            }
        }
        IndexSearcher searcher = searcherManager.acquire();
        try {
            TermQuery q = new TermQuery(new Term(IndexFields.DOC_TYPE, IndexFields.TYPE_FILE));
            int count = searcher.count(q);
            if (count == 0) {
                return;
            }
            TopDocs top = searcher.search(q, count);
            for (ScoreDoc sd : top.scoreDocs) {
                Document d = searcher.storedFields().document(sd.doc);
                manifest.put(d.get(IndexFields.FILE_KEY), IndexFields.fileState(d));
            }
        } finally {
            searcherManager.release(searcher);
        }
    }

    private static List<Map.Entry<String, String>> toList(Iterable<Map.Entry<String, String>> it) {
        List<Map.Entry<String, String>> list = new ArrayList<>();
        it.forEach(list::add);
        return list;
    }

    // ---- manifest ----

    public FileState state(String rel) {
        return manifest.get(rel);
    }

    public Set<String> manifestKeys() {
        return manifest.keySet();
    }

    public int filesIndexed() {
        int n = 0;
        for (FileState s : manifest.values()) {
            if (s.skippedReason() == null) {
                n++;
            }
        }
        return n;
    }

    public int chunksIndexed() {
        int n = 0;
        for (FileState s : manifest.values()) {
            n += s.chunkCount();
        }
        return n;
    }

    // ---- writes ----

    /** Atomically replaces every document of a file (manifest doc and chunks). */
    public void replaceFile(String rel, List<TokenizedChunk> chunks, FileState state) throws IOException {
        List<Document> docs = new ArrayList<>(chunks.size() + 1);
        docs.add(IndexFields.fileDoc(rel, state));
        for (TokenizedChunk c : chunks) {
            docs.add(IndexFields.chunkDoc(rel, c));
        }
        writer.updateDocuments(new Term(IndexFields.FILE_KEY, rel), docs);
        manifest.put(rel, state);
        dirty = true;
    }

    /** Updates only the manifest entry (stat values changed but the content hash did not). */
    public void touchManifest(String rel, FileState state) throws IOException {
        writer.updateDocument(new Term(IndexFields.MANIFEST_KEY, rel), IndexFields.fileDoc(rel, state));
        manifest.put(rel, state);
        dirty = true;
    }

    public void deleteFile(String rel) throws IOException {
        writer.deleteDocuments(new Term(IndexFields.FILE_KEY, rel));
        manifest.remove(rel);
        dirty = true;
    }

    /** Deletes the path itself and everything under it. */
    public void deletePrefix(String relDir) throws IOException {
        String prefix = relDir.endsWith("/") ? relDir : relDir + "/";
        writer.deleteDocuments(new Term(IndexFields.FILE_KEY, relDir));
        writer.deleteDocuments(new PrefixQuery(new Term(IndexFields.FILE_KEY, prefix)));
        manifest.keySet().removeIf(k -> k.equals(relDir) || k.startsWith(prefix));
        dirty = true;
    }

    /** Drops every document; used by a full rebuild. */
    public void deleteAll() throws IOException {
        writer.deleteAll();
        manifest.clear();
        lastFullScanAt = 0;
        dirty = true;
        commit();
        refresh();
    }

    public long lastFullScanAt() {
        return lastFullScanAt;
    }

    public void setLastFullScanAt(long epochMs) {
        this.lastFullScanAt = epochMs;
        this.dirty = true;
    }

    // ---- commit / refresh ----

    public void maybeCommit() throws IOException {
        if (dirty && System.currentTimeMillis() - lastCommitMs >= commitIntervalMs) {
            commit();
        }
    }

    public synchronized void commit() throws IOException {
        if (closed) {
            return;
        }
        writer.setLiveCommitData(Map.of(COMMIT_LAST_FULL_SCAN, Long.toString(lastFullScanAt)).entrySet());
        writer.commit();
        lastCommitMs = System.currentTimeMillis();
        dirty = false;
    }

    public void refresh() throws IOException {
        if (!closed) {
            searcherManager.maybeRefresh();
        }
    }

    // ---- reads ----

    public IndexSearcher acquire() throws IOException {
        pins.incrementAndGet();
        try {
            return searcherManager.acquire();
        } catch (IOException | RuntimeException e) {
            pins.decrementAndGet();
            throw e;
        }
    }

    public void release(IndexSearcher searcher) {
        try {
            searcherManager.release(searcher);
        } catch (IOException ignored) {
            // nothing useful to do
        } finally {
            pins.decrementAndGet();
        }
    }

    /** Marks the index as in use by a long-running job so it is not evicted. */
    public void pin() {
        pins.incrementAndGet();
    }

    public void unpin() {
        pins.decrementAndGet();
    }

    public boolean isPinned() {
        return pins.get() > 0;
    }

    public long indexBytes() {
        long total = 0;
        try {
            for (String f : directory.listAll()) {
                try {
                    total += directory.fileLength(f);
                } catch (IOException ignored) {
                    // file removed by a merge between listAll and fileLength
                }
            }
        } catch (IOException ignored) {
            // report what we have
        }
        return total;
    }

    public Path directory() {
        return dir;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        try {
            commit();
        } finally {
            closed = true;
            try {
                searcherManager.close();
            } finally {
                try {
                    writer.close();
                } finally {
                    directory.close();
                }
            }
        }
    }

    public boolean isClosed() {
        return closed;
    }
}
