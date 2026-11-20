package com.contextcompresso.vatstash.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/** Turns absolute paths from clients into workspace-relative ones, refusing anything outside the root. */
public final class PathGuard {

    private PathGuard() {
    }

    /**
     * Returns the '/'-separated path of {@code absPath} relative to {@code root}, or empty when the
     * path is not absolute, escapes the root (including through symlinks or {@code ..}) or is the
     * root itself. The path does not need to exist, so deletions can be checked.
     */
    public static Optional<String> relativize(Path root, String absPath) {
        if (absPath == null || absPath.isBlank()) {
            return Optional.empty();
        }
        try {
            Path p = Paths.get(absPath);
            if (!p.isAbsolute()) {
                return Optional.empty();
            }
            Path rootReal = realOrSelf(root);
            Path real = resolveReal(p.normalize());
            if (!real.startsWith(rootReal) || real.equals(rootReal)) {
                return Optional.empty();
            }
            return Optional.of(rootReal.relativize(real).toString().replace('\\', '/'));
        } catch (InvalidPathException | IOException e) {
            return Optional.empty();
        }
    }

    private static Path realOrSelf(Path root) {
        try {
            return root.toRealPath();
        } catch (IOException e) {
            return root.toAbsolutePath().normalize();
        }
    }

    private static Path resolveReal(Path p) throws IOException {
        if (Files.exists(p)) {
            return p.toRealPath();
        }
        Path ancestor = p.getParent();
        while (ancestor != null && !Files.exists(ancestor)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            return p;
        }
        return ancestor.toRealPath().resolve(ancestor.relativize(p)).normalize();
    }
}
