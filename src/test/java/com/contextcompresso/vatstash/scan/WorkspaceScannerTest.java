package com.contextcompresso.vatstash.scan;

import com.contextcompresso.vatstash.chunk.LanguageDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceScannerTest {

    @TempDir
    Path tmp;

    private final WorkspaceScanner scanner = new WorkspaceScanner(new LanguageDetector());

    private void write(Path root, String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void skipsBinaryOversizeAndIgnoredFiles() throws IOException {
        Path root = tmp.toRealPath();
        write(root, "src/Main.java", "class Main {}\n");
        write(root, "src/app.ts", "export const a = 1;\n");
        write(root, "src/big.java", "// " + "x".repeat(5000) + "\n");
        write(root, "src/gen.log.java", "class Gen {}\n");
        write(root, "node_modules/lib/index.js", "module.exports = 1;\n");
        write(root, ".gitignore", "*.log.java\n");
        Files.write(root.resolve("src/blob.java"), new byte[]{'c', 'l', 0, 1, 2, 3});
        Files.write(root.resolve("src/image.png"), new byte[]{1, 2, 3});

        ScanResult result = scanner.scan(root, new IgnoreRules(root, List.of()), 1000, 100);

        assertThat(result.files()).extracting(ScannedFile::rel).containsExactly("src/Main.java", "src/app.ts");
        assertThat(result.files()).extracting(ScannedFile::language).containsExactly("java", "typescript");
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.truncated()).isFalse();
    }

    @Test
    void doesNotFollowSymlinkOutsideRoot() throws IOException {
        Path root = Files.createDirectory(tmp.resolve("ws")).toRealPath();
        Path outside = Files.createDirectory(tmp.resolve("outside")).toRealPath();
        write(outside, "Secret.java", "class Secret {}\n");
        write(root, "Inside.java", "class Inside {}\n");
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
            Files.createSymbolicLink(root.resolve("Linked.java"), outside.resolve("Secret.java"));
        } catch (UnsupportedOperationException | IOException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symlinks unavailable");
        }

        ScanResult result = scanner.scan(root, new IgnoreRules(root, List.of()), 100_000, 100);

        assertThat(result.files()).extracting(ScannedFile::rel).containsExactly("Inside.java");
    }

    @Test
    void stopsAtMaxFilesAndFlagsTruncated() throws IOException {
        Path root = tmp.toRealPath();
        for (int i = 0; i < 10; i++) {
            write(root, "f" + i + ".java", "class F" + i + " {}\n");
        }

        ScanResult result = scanner.scan(root, new IgnoreRules(root, List.of()), 100_000, 4);

        assertThat(result.files()).hasSize(4);
        assertThat(result.truncated()).isTrue();

        ScanResult all = scanner.scan(root, new IgnoreRules(root, List.of()), 100_000, 10);
        assertThat(all.files()).hasSize(10);
        assertThat(all.truncated()).isFalse();
        assertThat(IntStream.range(0, 10)).allMatch(i -> all.files().get(i).rel().equals("f" + i + ".java"));
    }
}
