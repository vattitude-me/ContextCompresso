package com.contextcompresso.vatstash.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class PathGuardTest {

    @TempDir
    Path tmp;

    @Test
    void rejectsTraversalAndOutsidePaths() throws IOException {
        Path root = Files.createDirectory(tmp.resolve("ws")).toRealPath();
        Path outside = Files.createDirectory(tmp.resolve("outside")).toRealPath();
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/A.java"), "class A {}\n");

        assertThat(PathGuard.relativize(root, root.resolve("src/A.java").toString())).contains("src/A.java");
        assertThat(PathGuard.relativize(root, root.resolve("src/Deleted.java").toString())).contains("src/Deleted.java");
        assertThat(PathGuard.relativize(root, root.resolve("src/../src/A.java").toString())).contains("src/A.java");

        assertThat(PathGuard.relativize(root, root.toString())).isEmpty();
        assertThat(PathGuard.relativize(root, root.resolve("../outside/B.java").toString())).isEmpty();
        assertThat(PathGuard.relativize(root, outside.resolve("B.java").toString())).isEmpty();
        assertThat(PathGuard.relativize(root, "src/A.java")).isEmpty();
        assertThat(PathGuard.relativize(root, "")).isEmpty();
        assertThat(PathGuard.relativize(root, null)).isEmpty();
        assertThat(PathGuard.relativize(root, root + "-sibling/A.java")).isEmpty();

        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
            assertThat(PathGuard.relativize(root, root.resolve("escape/B.java").toString())).isEmpty();
        } catch (UnsupportedOperationException | IOException e) {
            // symlinks unavailable on this platform
        }
    }
}
