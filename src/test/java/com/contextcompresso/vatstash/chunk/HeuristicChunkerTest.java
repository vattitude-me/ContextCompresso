package com.contextcompresso.vatstash.chunk;

import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class HeuristicChunkerTest {

    private final HeuristicChunker chunker = new HeuristicChunker(3, 40, 5, 6000);

    private static String lines(String prefix, int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> prefix + i).collect(Collectors.joining("\n"));
    }

    @Test
    void typescriptTopLevelFunctionsBecomeChunks() {
        String src = "export function alpha(a: number) {\n" + lines("  const a", 5) + "\n}\n\n"
                + "export function beta(b: string) {\n" + lines("  const b", 5) + "\n}\n\n"
                + "export class Gamma {\n" + lines("  field", 5) + "\n}\n";

        List<CodeChunk> chunks = chunker.chunk("src/x.ts", "typescript", src);

        assertThat(chunks).hasSizeGreaterThanOrEqualTo(3);
        assertThat(chunks).extracting(CodeChunk::symbol).contains("alpha", "beta", "Gamma");
        CodeChunk beta = chunks.stream().filter(c -> "beta".equals(c.symbol())).findFirst().orElseThrow();
        assertThat(beta.content()).startsWith("export function beta").doesNotContain("function alpha");
    }

    @Test
    void pythonSplitsOnTopLevelDefAndClass() {
        String src = "import os\nimport sys\nimport json\n\n"
                + "def load(path):\n" + lines("    x = ", 5) + "\n\n"
                + "class Repo:\n" + lines("    attr = ", 5) + "\n\n"
                + "async def fetch():\n" + lines("    y = ", 5) + "\n";

        List<CodeChunk> chunks = chunker.chunk("repo.py", "python", src);

        assertThat(chunks).extracting(CodeChunk::symbol).contains("load", "Repo", "fetch");
        assertThat(chunks).filteredOn(c -> "Repo".equals(c.symbol()))
                .allSatisfy(c -> assertThat(c.content()).doesNotContain("def load"));
    }

    @Test
    void markdownSplitsOnHeadingsWithHeadingAsSymbol() {
        String src = "# Title\n\nintro text one\nintro text two\n\n"
                + "## Install\n\nrun the installer\nthen restart\n\n"
                + "## Usage\n\ncall the tool\nread the output\n";

        List<CodeChunk> chunks = chunker.chunk("README.md", "markdown", src);

        assertThat(chunks).extracting(CodeChunk::symbol).contains("Title", "Install", "Usage");
        assertThat(chunks).filteredOn(c -> "Install".equals(c.symbol()))
                .allSatisfy(c -> assertThat(c.kind()).isEqualTo("section"));
    }

    @Test
    void hardSplitRespectsMaxLinesAndOverlap() {
        String src = lines("x = ", 130);

        List<CodeChunk> chunks = chunker.chunk("flat.py", "python", src);

        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks).allSatisfy(c -> assertThat(c.endLine() - c.startLine() + 1).isLessThanOrEqualTo(40));
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).startLine()).isLessThanOrEqualTo(chunks.get(i - 1).endLine());
            assertThat(chunks.get(i).startLine()).isGreaterThan(chunks.get(i - 1).startLine());
        }
    }

    @Test
    void everyLineIsCoveredAndLineNumbersAreOneBased() {
        String src = "package a;\n\nfunction one() {\n  return 1;\n}\n\n" + lines("// note ", 60) + "\n\nfunction two() {\n"
                + lines("  call", 12) + "\n}\n";
        int total = src.split("\n", -1).length - 1;

        List<CodeChunk> chunks = chunker.chunk("a.js", "javascript", src);

        BitSet covered = new BitSet();
        for (CodeChunk c : chunks) {
            assertThat(c.startLine()).isGreaterThanOrEqualTo(1);
            assertThat(c.endLine()).isGreaterThanOrEqualTo(c.startLine());
            covered.set(c.startLine(), c.endLine() + 1);
        }
        assertThat(chunks.get(0).startLine()).isEqualTo(1);
        for (int line = 1; line <= total; line++) {
            assertThat(covered.get(line)).as("line %d covered", line).isTrue();
        }
    }
}
