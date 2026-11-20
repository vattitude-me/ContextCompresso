package com.contextcompresso.vatstash.scan;

import com.contextcompresso.vatstash.chunk.LanguageDetector;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/** Walks a workspace without following symlinks and returns the files worth indexing. */
@Component
public class WorkspaceScanner {

    private static final int BINARY_SNIFF_BYTES = 8192;

    private final LanguageDetector languageDetector;

    public WorkspaceScanner(LanguageDetector languageDetector) {
        this.languageDetector = languageDetector;
    }

    public ScanResult scan(Path root, IgnoreRules rules, long maxFileBytes, int maxFiles) throws IOException {
        List<ScannedFile> files = new ArrayList<>();
        int[] skipped = {0};
        boolean[] truncated = {false};

        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (dir.equals(root)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String rel = relOf(root, dir);
                        return rules.isIgnored(rel, true) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (attrs.isSymbolicLink() || !attrs.isRegularFile()) {
                            return FileVisitResult.CONTINUE;
                        }
                        String rel = relOf(root, file);
                        if (rules.isIgnored(rel, false)) {
                            return FileVisitResult.CONTINUE;
                        }
                        Optional<String> language = languageDetector.detect(file.getFileName().toString(),
                                attrs.size());
                        if (language.isEmpty()) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (attrs.size() > maxFileBytes || looksBinary(file)) {
                            skipped[0]++;
                            return FileVisitResult.CONTINUE;
                        }
                        if (files.size() >= maxFiles) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                        files.add(new ScannedFile(rel, file, attrs.size(), attrs.lastModifiedTime().toMillis(),
                                language.get()));
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        skipped[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                });
        files.sort(Comparator.comparing(ScannedFile::rel));
        return new ScanResult(files, skipped[0], truncated[0]);
    }

    /**
     * Checks one file the way {@link #scan} would. Returns empty when it is missing, not a regular
     * file, ignored, of an unknown language, too large or binary.
     */
    public Optional<ScannedFile> inspect(Path root, String rel, IgnoreRules rules, long maxFileBytes) {
        Path file = root.resolve(rel);
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
        if (attrs.isSymbolicLink() || !attrs.isRegularFile() || rules.isIgnored(rel, false)) {
            return Optional.empty();
        }
        Optional<String> language = languageDetector.detect(file.getFileName().toString(), attrs.size());
        if (language.isEmpty() || attrs.size() > maxFileBytes || looksBinary(file)) {
            return Optional.empty();
        }
        return Optional.of(new ScannedFile(rel, file, attrs.size(), attrs.lastModifiedTime().toMillis(),
                language.get()));
    }

    static String relOf(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    static boolean looksBinary(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = in.readNBytes(BINARY_SNIFF_BYTES);
            for (byte b : buf) {
                if (b == 0) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
