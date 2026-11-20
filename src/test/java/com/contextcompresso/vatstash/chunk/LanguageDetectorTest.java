package com.contextcompresso.vatstash.chunk;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageDetectorTest {

    private final LanguageDetector detector = new LanguageDetector();

    @Test
    void detectsByExtensionAndFilename() {
        assertThat(detector.detect(Path.of("src/Main.java"))).contains("java");
        assertThat(detector.detect(Path.of("a/b/app.tsx"))).contains("typescript");
        assertThat(detector.detect(Path.of("tool.py"))).contains("python");
        assertThat(detector.detect(Path.of("docker/Dockerfile"))).contains("dockerfile");
        assertThat(detector.detect(Path.of("Makefile"))).contains("makefile");
        assertThat(detector.detect(Path.of("ci/Jenkinsfile"))).contains("jenkinsfile");
        assertThat(detector.detect(Path.of("README.md"))).contains("markdown");
        assertThat(detector.detect(Path.of("photo.png"))).isEmpty();
        assertThat(detector.detect(Path.of("noextension"))).isEmpty();
        assertThat(LanguageDetector.normalizeLanguage("TS")).isEqualTo("typescript");
    }

    @Test
    void rejectsLockfilesMinifiedAndLargeJson() {
        assertThat(detector.detect("package-lock.json", 10)).isEmpty();
        assertThat(detector.detect("yarn.lock", 10)).isEmpty();
        assertThat(detector.detect("pnpm-lock.yaml", 10)).isEmpty();
        assertThat(detector.detect("bundle.min.js", 10)).isEmpty();
        assertThat(detector.detect("bundle.js.map", 10)).isEmpty();
        assertThat(detector.detect("data.json", 100L * 1024 + 1)).isEmpty();
        assertThat(detector.detect("data.json", 1024)).contains("json");
    }
}
