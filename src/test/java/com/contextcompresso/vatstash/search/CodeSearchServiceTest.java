package com.contextcompresso.vatstash.search;

import com.contextcompresso.vatstash.VatStashException;
import com.contextcompresso.vatstash.VatStashTestSupport;
import com.contextcompresso.vatstash.VatStashTestSupport.Env;
import com.contextcompresso.vatstash.workspace.WorkspaceRecord;
import com.contextcompresso.vatstash.workspace.WorkspaceSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.contextcompresso.vatstash.VatStashTestSupport.write;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodeSearchServiceTest {

    @TempDir
    Path tmp;

    Env env;

    @AfterEach
    void tearDown() {
        if (env != null) {
            env.close();
        }
    }

    private Path newRoot(String name) throws IOException {
        return Files.createDirectory(tmp.resolve(name)).toRealPath();
    }

    private static SearchRequest req(WorkspaceRecord ws, String query) {
        return new SearchRequest(query, List.of(ws.workspaceId()), null, null, null, null, null, "test");
    }

    private static String method(String name, String comment) {
        return "    public int " + name + "(int input) {\n"
                + (comment == null ? "" : "        // " + comment + "\n")
                + "        int a = input + 1;\n"
                + "        int b = a * 2;\n"
                + "        int c = b - 3;\n"
                + "        return c;\n"
                + "    }\n";
    }

    @Test
    void symbolMatchOutranksCommentMention() throws IOException {
        Path root = newRoot("ws");
        write(root, "src/Checker.java", "public class Checker {\n" + method("checksumcalc", null) + "}\n");
        write(root, "src/Renderer.java", "public class Renderer {\n"
                + method("render", "calls checksumcalc before drawing") + "}\n");
        env = VatStashTestSupport.env(tmp);
        WorkspaceRecord ws = env.registerAndIndex(root);

        SearchResponse res = env.search().search(req(ws, "checksumcalc"));

        assertThat(res.results()).isNotEmpty();
        assertThat(res.results().get(0).path()).isEqualTo("src/Checker.java");
        assertThat(res.results().get(0).symbol()).isEqualTo("Checker.checksumcalc");
        assertThat(res.results()).extracting(SearchHit::path).contains("src/Renderer.java");
        assertThat(res.text()).startsWith(SearchResultFormatter.MARKER);
    }

    @Test
    void pathPrefixAndLanguageFiltersApply() throws IOException {
        Path root = newRoot("ws");
        write(root, "server/Svc.java", "public class Svc {\n" + method("sharedterm", null) + "}\n");
        write(root, "web/ui.ts", "export function sharedterm(x: number) {\n  const a = x + 1;\n  const b = a * 2;\n"
                + "  const c = b - 1;\n  return c;\n}\n");
        env = VatStashTestSupport.env(tmp);
        WorkspaceRecord ws = env.registerAndIndex(root);

        SearchResponse all = env.search().search(req(ws, "sharedterm"));
        assertThat(all.results()).extracting(SearchHit::path).contains("server/Svc.java", "web/ui.ts");

        SearchResponse web = env.search().search(new SearchRequest("sharedterm", List.of(ws.workspaceId()), null,
                null, null, "web/", null, "test"));
        assertThat(web.results()).isNotEmpty().allSatisfy(h -> assertThat(h.path()).startsWith("web/"));

        SearchResponse java = env.search().search(new SearchRequest("sharedterm", List.of(ws.workspaceId()), null,
                null, null, null, List.of("java"), "test"));
        assertThat(java.results()).isNotEmpty().allSatisfy(h -> assertThat(h.language()).isEqualTo("java"));

        SearchResponse none = env.search().search(new SearchRequest("sharedterm", List.of(ws.workspaceId()), null,
                null, null, "web/", List.of("java"), "test"));
        assertThat(none.results()).isEmpty();
    }

    @Test
    void respectsTokenBudgetAndReportsOmitted() throws IOException {
        Path root = newRoot("ws");
        for (int i = 0; i < 12; i++) {
            write(root, "src/Part" + i + ".java", "public class Part" + i + " {\n" + method("budgetterm", null) + "}\n");
        }
        env = VatStashTestSupport.env(tmp);
        WorkspaceRecord ws = env.registerAndIndex(root);

        SearchResponse unlimited = env.search().search(new SearchRequest("budgetterm", List.of(ws.workspaceId()),
                null, 30, 16000, null, null, "test"));
        int available = unlimited.results().size();
        assertThat(available).isGreaterThan(4);
        assertThat(unlimited.totals().omittedByBudget()).isZero();

        SearchResponse tight = env.search().search(new SearchRequest("budgetterm", List.of(ws.workspaceId()), null,
                30, 150, null, null, "test"));

        assertThat(tight.results().size()).isLessThan(available);
        assertThat(tight.totals().omittedByBudget()).isEqualTo(Math.min(available, 30) - tight.results().size());
        assertThat(tight.totals().omittedByBudget()).isGreaterThan(0);
        int snippetTokens = tight.results().stream().mapToInt(h -> h.tokens() + 12).sum();
        assertThat(snippetTokens).isLessThanOrEqualTo(150);
        assertThat(tight.text()).contains("omitted to stay within the 150-token budget");
    }

    @Test
    void capsChunksPerFile() throws IOException {
        Path root = newRoot("ws");
        StringBuilder body = new StringBuilder("public class Many {\n");
        for (int i = 1; i <= 8; i++) {
            body.append(method("capterm" + i, null)).append('\n');
        }
        body.append("}\n");
        write(root, "src/Many.java", body.toString());
        env = VatStashTestSupport.env(tmp);
        WorkspaceRecord ws = env.registerAndIndex(root);

        SearchResponse res = env.search().search(req(ws, "capterm"));

        assertThat(res.results()).isNotEmpty();
        assertThat(res.results().size()).isLessThanOrEqualTo(env.props().maxChunksPerFile());
        assertThat(res.results()).allSatisfy(h -> assertThat(h.path()).isEqualTo("src/Many.java"));
    }

    @Test
    void searchesAcrossMultipleWorkspaces() throws IOException {
        Path one = newRoot("one");
        Path two = newRoot("two");
        write(one, "A.java", "public class A {\n" + method("crossterm", null) + "}\n");
        write(two, "B.java", "public class B {\n" + method("crossterm", null) + "}\n");
        env = VatStashTestSupport.env(tmp);
        WorkspaceRecord wsOne = env.registerAndIndex(one);
        WorkspaceRecord wsTwo = env.registerAndIndex(two);

        SearchResponse res = env.search().search(new SearchRequest("crossterm",
                List.of(wsOne.workspaceId(), wsTwo.workspaceId()), null, null, null, null, null, "test"));

        Set<String> workspaces = res.results().stream().map(SearchHit::workspaceId).collect(Collectors.toSet());
        assertThat(workspaces).containsExactlyInAnyOrder(wsOne.workspaceId(), wsTwo.workspaceId());
        assertThat(res.results()).filteredOn(h -> h.workspaceId().equals(wsOne.workspaceId()))
                .allSatisfy(h -> {
                    assertThat(h.path()).isEqualTo("A.java");
                    assertThat(h.absolutePath()).isEqualTo(one.resolve("A.java").toString());
                });
        assertThat(res.results()).filteredOn(h -> h.workspaceId().equals(wsTwo.workspaceId()))
                .allSatisfy(h -> assertThat(h.path()).isEqualTo("B.java"));

        SearchResponse byRoot = env.search().search(new SearchRequest("crossterm", null, two.toString(), null, null,
                null, null, "test"));
        assertThat(byRoot.results()).isNotEmpty().allSatisfy(h -> assertThat(h.path()).isEqualTo("B.java"));
    }

    @Test
    void returnsStaleFreshnessWhileBuilding() throws IOException {
        Path root = newRoot("ws");
        for (int i = 0; i < 60; i++) {
            write(root, "src/File" + i + ".java", "public class File" + i + " {\n" + method("slowterm", null) + "}\n");
        }
        env = VatStashTestSupport.env(tmp, p -> VatStashTestSupport.withIndexingPause(p, 40));
        WorkspaceRecord ws = env.registry().register(root.toString(), WorkspaceSettings.empty());
        env.registry().openIndex(ws.workspaceId());
        env.indexer().requestReconcile(ws.workspaceId());

        SearchResponse res = env.search().search(req(ws, "slowterm"));

        assertThat(res.freshness()).isEqualTo("stale");
        assertThat(res.text()).startsWith(SearchResultFormatter.MARKER).contains("results may be incomplete");
        assertThat(env.indexer().awaitIdle(ws.workspaceId(), Duration.ofSeconds(60))).isTrue();
        assertThat(env.search().search(req(ws, "slowterm")).freshness()).isEqualTo("fresh");
    }

    @Test
    void sourceFileTokensSumsDistinctFiles() throws IOException {
        Path root = newRoot("ws");
        write(root, "src/One.java", "public class One {\n" + method("sumterm", null) + method("sumterm2", null) + "}\n");
        write(root, "src/Two.java", "public class Two {\n" + method("sumterm", null) + "}\n");
        env = VatStashTestSupport.env(tmp);
        WorkspaceRecord ws = env.registerAndIndex(root);

        SearchResponse res = env.search().search(req(ws, "sumterm"));

        Set<String> files = res.results().stream().map(SearchHit::path).collect(Collectors.toSet());
        assertThat(files).containsExactlyInAnyOrder("src/One.java", "src/Two.java");
        assertThat(res.results().size()).isGreaterThan(files.size());
        long expected = files.stream()
                .mapToLong(f -> env.registry().openIndex(ws.workspaceId()).state(f).fileTokens())
                .sum();
        assertThat(res.totals().sourceFileTokens()).isEqualTo(expected);
        assertThat(res.totals().filesMatched()).isEqualTo(2);
        assertThat(res.totals().chunksReturned()).isEqualTo(res.results().size());
        assertThat(res.totals().returnedTokens()).isGreaterThan(0);
    }

    @Test
    void blankQueryRejected() throws IOException {
        Path root = newRoot("ws");
        write(root, "A.java", "public class A {}\n");
        env = VatStashTestSupport.env(tmp);
        WorkspaceRecord ws = env.registerAndIndex(root);

        assertThatThrownBy(() -> env.search().search(req(ws, "   ")))
                .isInstanceOfSatisfying(VatStashException.class, e -> assertThat(e.code()).isEqualTo("invalid_request"));
        assertThatThrownBy(() -> env.search().search(req(ws, null)))
                .isInstanceOf(VatStashException.class);
        assertThatThrownBy(() -> env.search().search(new SearchRequest("x", null, null, null, null, null, null, null)))
                .isInstanceOfSatisfying(VatStashException.class, e -> assertThat(e.code()).isEqualTo("invalid_request"));
        assertThatThrownBy(() -> env.search().search(new SearchRequest("x", List.of("nope"), null, null, null, null,
                null, null)))
                .isInstanceOfSatisfying(VatStashException.class,
                        e -> assertThat(e.code()).isEqualTo("workspace_not_found"));
    }
}
