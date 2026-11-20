package com.contextcompresso.vatstash.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IgnoreRulesTest {

    @TempDir
    Path root;

    private void write(String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private IgnoreRules rules(String... excludeGlobs) {
        return new IgnoreRules(root, List.of(excludeGlobs));
    }

    @Test
    void starGlobAndDirectoryOnlyPatterns() throws IOException {
        write(".gitignore", "*.log\ncache/\n");
        IgnoreRules r = rules();

        assertThat(r.isIgnored("debug.log", false)).isTrue();
        assertThat(r.isIgnored("a/b/trace.log", false)).isTrue();
        assertThat(r.isIgnored("src/Main.java", false)).isFalse();
        assertThat(r.isIgnored("cache", true)).isTrue();
        assertThat(r.isIgnored("src/cache", true)).isTrue();
        assertThat(r.isIgnored("cache/data.txt", false)).isTrue();
        assertThat(r.isIgnored("cache", false)).isFalse();
    }

    @Test
    void anchoredLeadingSlash() throws IOException {
        write(".gitignore", "/secret.txt\n/docs/gen\n");
        IgnoreRules r = rules();

        assertThat(r.isIgnored("secret.txt", false)).isTrue();
        assertThat(r.isIgnored("sub/secret.txt", false)).isFalse();
        assertThat(r.isIgnored("docs/gen", true)).isTrue();
        assertThat(r.isIgnored("other/docs/gen", true)).isFalse();
    }

    @Test
    void doubleStarMatchesAnyDepth() throws IOException {
        write(".gitignore", "**/generated/**\nsrc/**/*.tmp\n");
        IgnoreRules r = rules();

        assertThat(r.isIgnored("generated/A.java", false)).isTrue();
        assertThat(r.isIgnored("a/b/generated/c/A.java", false)).isTrue();
        assertThat(r.isIgnored("src/x/y/z.tmp", false)).isTrue();
        assertThat(r.isIgnored("src/z.tmp", false)).isTrue();
        assertThat(r.isIgnored("lib/z.tmp", false)).isFalse();
    }

    @Test
    void negationReincludes() throws IOException {
        write(".gitignore", "*.log\n!keep.log\n");
        IgnoreRules r = rules();

        assertThat(r.isIgnored("a.log", false)).isTrue();
        assertThat(r.isIgnored("keep.log", false)).isFalse();
        assertThat(r.isIgnored("sub/keep.log", false)).isFalse();
    }

    @Test
    void nestedGitignoreScopedToSubtree() throws IOException {
        write("pkg/.gitignore", "local.txt\n/only-here.txt\n");
        IgnoreRules r = rules();

        assertThat(r.isIgnored("pkg/local.txt", false)).isTrue();
        assertThat(r.isIgnored("pkg/deep/local.txt", false)).isTrue();
        assertThat(r.isIgnored("local.txt", false)).isFalse();
        assertThat(r.isIgnored("other/local.txt", false)).isFalse();
        assertThat(r.isIgnored("pkg/only-here.txt", false)).isTrue();
        assertThat(r.isIgnored("pkg/deep/only-here.txt", false)).isFalse();
    }

    @Test
    void builtInDirsAlwaysExcluded() throws IOException {
        write(".gitignore", "!node_modules/\n!target/\n");
        IgnoreRules r = rules();

        assertThat(r.isIgnored("node_modules", true)).isTrue();
        assertThat(r.isIgnored("node_modules/lib/index.js", false)).isTrue();
        assertThat(r.isIgnored("target/classes/A.class", false)).isTrue();
        assertThat(r.isIgnored(".git/config", false)).isTrue();
        assertThat(r.isIgnored("a/b/dist/bundle.js", false)).isTrue();
        assertThat(r.isIgnored("src/main.js", false)).isFalse();
    }

    @Test
    void vatstashignoreAndExcludeGlobsApplied() throws IOException {
        write(".vatstashignore", "fixtures/\n");
        IgnoreRules r = rules("*.snap", "legacy/**");

        assertThat(r.isIgnored("fixtures/data.json", false)).isTrue();
        assertThat(r.isIgnored("src/a.snap", false)).isTrue();
        assertThat(r.isIgnored("legacy/old/Thing.java", false)).isTrue();
        assertThat(r.isIgnored("src/Thing.java", false)).isFalse();

        write(".vatstashignore", "fixtures/\nextra.txt\n");
        assertThat(r.isIgnored("extra.txt", false)).isFalse();
        r.reload();
        assertThat(r.isIgnored("extra.txt", false)).isTrue();
    }
}
