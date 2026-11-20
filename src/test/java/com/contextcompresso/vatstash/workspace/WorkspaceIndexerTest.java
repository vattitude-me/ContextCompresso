package com.contextcompresso.vatstash.workspace;

import com.contextcompresso.vatstash.VatStashTestSupport;
import com.contextcompresso.vatstash.VatStashTestSupport.Env;
import com.contextcompresso.vatstash.index.IndexStatus;
import com.contextcompresso.vatstash.search.SearchRequest;
import com.contextcompresso.vatstash.search.SearchResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.contextcompresso.vatstash.VatStashTestSupport.setMtime;
import static com.contextcompresso.vatstash.VatStashTestSupport.write;
import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceIndexerTest {

    @TempDir
    Path tmp;

    Path root;
    Env env;
    WorkspaceIndexer indexer;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tmp.resolve("ws")).toRealPath();
        write(root, "src/Alpha.java", "public class Alpha {\n    int quokkaold() {\n        return 1;\n    }\n}\n");
        write(root, "src/Beta.java", "public class Beta {\n    int marvelbeta() {\n        return 2;\n    }\n}\n");
        write(root, "web/app.ts", "export function gammahandler(x: number) {\n  return x + 1;\n}\n");
        env = VatStashTestSupport.env(tmp);
        indexer = env.indexer();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private SearchResponse search(WorkspaceRecord ws, String query) {
        return env.search().search(new SearchRequest(query, List.of(ws.workspaceId()), null, null, null, null, null,
                "test"));
    }

    private boolean settle(WorkspaceRecord ws) {
        return indexer.awaitIdle(ws.workspaceId(), Duration.ofSeconds(30));
    }

    @Test
    void bootstrapIndexesAllFilesAndReportsReady() {
        WorkspaceRecord ws = env.registry().register(root.toString(), WorkspaceSettings.empty());
        env.registry().openIndex(ws.workspaceId());
        indexer.requestReconcile(ws.workspaceId());
        assertThat(settle(ws)).isTrue();

        IndexStatus status = indexer.status(ws);
        assertThat(status.state()).isEqualTo("ready");
        assertThat(status.freshness()).isEqualTo("fresh");
        assertThat(status.filesIndexed()).isEqualTo(3);
        assertThat(status.chunksIndexed()).isGreaterThanOrEqualTo(3);
        assertThat(status.lastFullScanAt()).isGreaterThan(0);
        assertThat(status.pendingFiles()).isZero();
        assertThat(status.truncated()).isFalse();
        assertThat(status.lastError()).isNull();
        assertThat(search(ws, "gammahandler").results()).isNotEmpty();
    }

    @Test
    void modifiedFileReplacesItsChunksAtomically() throws IOException {
        WorkspaceRecord ws = env.registerAndIndex(root);
        assertThat(search(ws, "quokkaold").results()).isNotEmpty();

        write(root, "src/Alpha.java",
                "public class Alpha {\n    int quokkafresh() {\n        return 100;\n    }\n    // padding\n}\n");
        var result = indexer.enqueueChanges(ws, List.of(root.resolve("src/Alpha.java").toString()), List.of());
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(settle(ws)).isTrue();

        assertThat(search(ws, "quokkaold").results()).isEmpty();
        SearchResponse fresh = search(ws, "quokkafresh");
        assertThat(fresh.results()).isNotEmpty();
        assertThat(fresh.results()).allSatisfy(h -> assertThat(h.path()).isEqualTo("src/Alpha.java"));
        assertThat(env.registry().openIndex(ws.workspaceId()).filesIndexed()).isEqualTo(3);
    }

    @Test
    void deletedFileRemovesChunksAndManifest() throws IOException {
        WorkspaceRecord ws = env.registerAndIndex(root);
        Path beta = root.resolve("src/Beta.java");
        Files.delete(beta);

        indexer.enqueueChanges(ws, List.of(), List.of(beta.toString()));
        assertThat(settle(ws)).isTrue();

        assertThat(env.registry().openIndex(ws.workspaceId()).manifestKeys()).doesNotContain("src/Beta.java");
        assertThat(search(ws, "marvelbeta").results()).isEmpty();
        assertThat(indexer.status(ws).filesIndexed()).isEqualTo(2);
    }

    @Test
    void unchangedMtimeAndSizeSkipsReadAndHash() {
        WorkspaceRecord ws = env.registerAndIndex(root);
        long reads = indexer.filesRead();
        long chunked = indexer.filesChunked();
        long reconciles = indexer.reconcileCount();
        assertThat(reads).isEqualTo(3);

        indexer.requestReconcile(ws.workspaceId());
        assertThat(settle(ws)).isTrue();

        assertThat(indexer.reconcileCount()).isEqualTo(reconciles + 1);
        assertThat(indexer.filesRead()).isEqualTo(reads);
        assertThat(indexer.filesChunked()).isEqualTo(chunked);
        assertThat(indexer.manifestTouches()).isZero();
    }

    @Test
    void sameHashOnlyTouchesManifest() throws IOException {
        WorkspaceRecord ws = env.registerAndIndex(root);
        long chunked = indexer.filesChunked();
        Path beta = root.resolve("src/Beta.java");
        setMtime(beta, Files.getLastModifiedTime(beta).toMillis() + 60_000);

        indexer.requestReconcile(ws.workspaceId());
        assertThat(settle(ws)).isTrue();

        assertThat(indexer.manifestTouches()).isEqualTo(1);
        assertThat(indexer.filesChunked()).isEqualTo(chunked);
        assertThat(indexer.filesRead()).isEqualTo(4);
        assertThat(env.registry().openIndex(ws.workspaceId()).state("src/Beta.java").mtime())
                .isEqualTo(Files.getLastModifiedTime(beta).toMillis());
        assertThat(search(ws, "marvelbeta").results()).isNotEmpty();
    }

    @Test
    void reopenRestoresManifestAndResumesIncrementally() throws IOException {
        WorkspaceRecord ws = env.registerAndIndex(root);
        assertThat(indexer.filesRead()).isEqualTo(3);
        env.close();

        env = VatStashTestSupport.env(tmp);
        indexer = env.indexer();
        WorkspaceRecord again = env.registry().find(ws.workspaceId()).orElseThrow();
        assertThat(env.registry().openIndex(again.workspaceId()).filesIndexed()).isEqualTo(3);

        indexer.requestReconcile(again.workspaceId());
        assertThat(settle(again)).isTrue();
        assertThat(indexer.filesRead()).isZero();
        assertThat(indexer.status(again).state()).isEqualTo("ready");

        write(root, "src/Beta.java", "public class Beta {\n    int marvelrenamed() {\n        return 22;\n    }\n}\n");
        indexer.requestReconcile(again.workspaceId());
        assertThat(settle(again)).isTrue();
        assertThat(indexer.filesRead()).isEqualTo(1);
        assertThat(env.search().search(new SearchRequest("marvelrenamed", List.of(again.workspaceId()), null, null,
                null, null, null, "test")).results()).isNotEmpty();
    }

    @Test
    void directoryDeleteRemovesPrefix() throws IOException {
        write(root, "pkg/one/X.java", "public class X {\n    int xylophone() {\n        return 1;\n    }\n}\n");
        write(root, "pkg/one/Y.java", "public class Y {\n    int yodeler() {\n        return 1;\n    }\n}\n");
        write(root, "pkg/two/Z.java", "public class Z {\n    int zeppelin() {\n        return 1;\n    }\n}\n");
        WorkspaceRecord ws = env.registerAndIndex(root);
        assertThat(env.registry().openIndex(ws.workspaceId()).manifestKeys())
                .contains("pkg/one/X.java", "pkg/one/Y.java", "pkg/two/Z.java");

        Path dir = root.resolve("pkg/one");
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        indexer.enqueueChanges(ws, List.of(), List.of(dir.toString()));
        assertThat(settle(ws)).isTrue();

        assertThat(env.registry().openIndex(ws.workspaceId()).manifestKeys())
                .doesNotContain("pkg/one/X.java", "pkg/one/Y.java")
                .contains("pkg/two/Z.java");
        assertThat(search(ws, "xylophone").results()).isEmpty();
        assertThat(search(ws, "zeppelin").results()).isNotEmpty();
    }

    @Test
    void largeChangeBatchTriggersReconcile() throws IOException {
        WorkspaceRecord ws = env.registerAndIndex(root);
        long reconciles = indexer.reconcileCount();
        write(root, "src/Late.java", "public class Late {\n    int latecomer() {\n        return 3;\n    }\n}\n");

        List<String> many = new ArrayList<>();
        for (int i = 0; i <= WorkspaceIndexer.CHANGE_BATCH_LIMIT; i++) {
            many.add(root.resolve("nothing/File" + i + ".java").toString());
        }
        indexer.enqueueChanges(ws, many, List.of());
        assertThat(settle(ws)).isTrue();

        assertThat(indexer.reconcileCount()).isEqualTo(reconciles + 1);
        assertThat(search(ws, "latecomer").results()).isNotEmpty();
    }

    @Test
    void reconcileWithNoChangesStaysReady() {
        WorkspaceRecord ws = env.registerAndIndex(root);
        long reconciles = indexer.reconcileCount();

        List<String> seen = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            indexer.requestReconcile(ws.workspaceId());
            do {
                IndexStatus status = indexer.status(ws);
                if (!"ready".equals(status.state()) || !"fresh".equals(status.freshness())) {
                    seen.add(status.state() + "/" + status.freshness());
                }
            } while (indexer.isBusy(ws.workspaceId()));
        }
        assertThat(settle(ws)).isTrue();

        assertThat(indexer.reconcileCount()).isGreaterThan(reconciles);
        assertThat(seen).isEmpty();
        IndexStatus after = indexer.status(ws);
        assertThat(after.state()).isEqualTo("ready");
        assertThat(after.freshness()).isEqualTo("fresh");
    }

    @Test
    void reconcileThatFindsAModifiedFileReportsUpdating() throws IOException {
        env.close();
        Path slow = Files.createDirectory(tmp.resolve("slow"));
        env = VatStashTestSupport.env(slow, p -> VatStashTestSupport.withIndexingPause(p, 100));
        indexer = env.indexer();
        WorkspaceRecord ws = env.registerAndIndex(root);

        write(root, "src/Alpha.java",
                "public class Alpha {\n    int quokkaold() {\n        return 1;\n    }\n    // grown\n}\n");
        write(root, "src/Beta.java",
                "public class Beta {\n    int marvelbeta() {\n        return 2;\n    }\n    // grown\n}\n");
        write(root, "web/app.ts", "export function gammahandler(x: number) {\n  return x + 1;\n}\n// grown\n");
        indexer.requestReconcile(ws.workspaceId());

        IndexStatus updating = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (updating == null && System.nanoTime() < deadline) {
            IndexStatus status = indexer.status(ws);
            if ("updating".equals(status.state())) {
                updating = status;
            }
        }
        assertThat(updating).isNotNull();
        assertThat(updating.freshness()).isEqualTo("stale");

        assertThat(settle(ws)).isTrue();
        IndexStatus after = indexer.status(ws);
        assertThat(after.state()).isEqualTo("ready");
        assertThat(after.freshness()).isEqualTo("fresh");
    }
}
