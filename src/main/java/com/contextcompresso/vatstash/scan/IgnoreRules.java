package com.contextcompresso.vatstash.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * gitignore-style matching: {@code *}, {@code **}, {@code ?}, leading {@code /} (anchored),
 * trailing {@code /} (directories only) and {@code !} (negation). {@code .gitignore} and
 * {@code .vatstashignore} files apply only to their own subtree and are loaded lazily as paths are
 * checked. Rules from those files are evaluated first (deeper files after shallower ones), then the
 * user's {@code excludeGlobs}; the last matching rule wins. A fixed list of build and VCS
 * directories is always excluded and cannot be re-included.
 */
public class IgnoreRules {

    public static final Set<String> BUILT_IN_DIRS = Set.of(
            ".git", ".hg", ".svn", "node_modules", "target", "build", "out", "dist", ".gradle", ".idea",
            ".vscode", ".next", ".nuxt", "coverage", "__pycache__", ".venv", "venv", ".tox", "bin", "obj",
            "vendor", ".terraform");

    private static final String[] IGNORE_FILES = {".gitignore", ".vatstashignore"};

    private final Path root;
    private final List<Rule> fileRules = new CopyOnWriteArrayList<>();
    private final List<Rule> globRules = new ArrayList<>();
    private final Set<String> loadedDirs = new HashSet<>();

    public IgnoreRules(Path root, List<String> excludeGlobs) {
        this.root = root;
        if (excludeGlobs != null) {
            for (String glob : excludeGlobs) {
                Rule r = parse("", glob);
                if (r != null) {
                    globRules.add(r);
                }
            }
        }
    }

    public boolean isIgnored(Path rel, boolean isDir) {
        return isIgnored(rel.toString().replace('\\', '/'), isDir);
    }

    /** {@code rel} uses '/' separators and is relative to the workspace root. */
    public boolean isIgnored(String rel, boolean isDir) {
        String normalized = rel.startsWith("/") ? rel.substring(1) : rel;
        if (normalized.isEmpty()) {
            return false;
        }
        String[] parts = normalized.split("/");
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            boolean last = i == parts.length - 1;
            boolean dir = !last || isDir;
            String parentDir = prefix.toString();
            if (prefix.length() > 0) {
                prefix.append('/');
            }
            prefix.append(parts[i]);
            if (dir && BUILT_IN_DIRS.contains(parts[i])) {
                return true;
            }
            ensureLoaded(parentDir);
            if (matches(prefix.toString(), dir)) {
                return true;
            }
        }
        return false;
    }

    /** Drops cached .gitignore content so edited files are re-read on the next check. */
    public synchronized void reload() {
        fileRules.clear();
        loadedDirs.clear();
    }

    private boolean matches(String path, boolean isDir) {
        boolean ignored = false;
        for (Rule r : fileRules) {
            if (r.matches(path, isDir)) {
                ignored = !r.negated;
            }
        }
        for (Rule r : globRules) {
            if (r.matches(path, isDir)) {
                ignored = !r.negated;
            }
        }
        return ignored;
    }

    private synchronized void ensureLoaded(String dirRel) {
        if (!loadedDirs.add(dirRel)) {
            return;
        }
        Path dir = dirRel.isEmpty() ? root : root.resolve(dirRel);
        for (String name : IGNORE_FILES) {
            Path file = dir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                for (String line : text.split("\r?\n")) {
                    Rule r = parse(dirRel, line);
                    if (r != null) {
                        fileRules.add(r);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // unreadable ignore file: treat as absent
            }
        }
    }

    // ---- pattern parsing ----

    private static Rule parse(String baseDir, String rawLine) {
        if (rawLine == null) {
            return null;
        }
        String line = rawLine;
        // trailing spaces are ignored unless escaped
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ' && (end < 2 || line.charAt(end - 2) != '\\')) {
            end--;
        }
        line = line.substring(0, end);
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        boolean negated = false;
        if (line.startsWith("!")) {
            negated = true;
            line = line.substring(1);
        } else if (line.startsWith("\\!") || line.startsWith("\\#")) {
            line = line.substring(1);
        }
        boolean dirOnly = false;
        if (line.endsWith("/")) {
            dirOnly = true;
            line = line.substring(0, line.length() - 1);
        }
        if (line.isEmpty()) {
            return null;
        }
        boolean anchored = false;
        if (line.startsWith("/")) {
            anchored = true;
            line = line.substring(1);
        } else if (line.indexOf('/') >= 0) {
            anchored = true;
        }
        String regex = (anchored ? "" : "(?:.*/)?") + toRegex(line);
        try {
            return new Rule(baseDir, Pattern.compile(regex), negated, dirOnly);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    private static String toRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        int n = glob.length();
        for (int i = 0; i < n; i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < n && glob.charAt(i + 1) == '*') {
                        boolean atStart = i == 0 || glob.charAt(i - 1) == '/';
                        if (atStart && i + 2 < n && glob.charAt(i + 2) == '/') {
                            sb.append("(?:.*/)?");
                            i += 2;
                        } else {
                            sb.append(".*");
                            i += 1;
                        }
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append("[^/]");
                case '[' -> {
                    int close = glob.indexOf(']', i + 2);
                    if (close < 0) {
                        sb.append("\\[");
                    } else {
                        String cls = glob.substring(i + 1, close);
                        if (cls.startsWith("!")) {
                            cls = "^" + cls.substring(1);
                        }
                        sb.append('[').append(cls.replace("\\", "\\\\")).append(']');
                        i = close;
                    }
                }
                case '\\' -> {
                    if (i + 1 < n) {
                        sb.append(Pattern.quote(String.valueOf(glob.charAt(i + 1))));
                        i++;
                    }
                }
                default -> {
                    if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                        sb.append('\\');
                    }
                    sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private record Rule(String baseDir, Pattern pattern, boolean negated, boolean dirOnly) {
        boolean matches(String path, boolean isDir) {
            if (dirOnly && !isDir) {
                return false;
            }
            String relative = path;
            if (!baseDir.isEmpty()) {
                if (!path.startsWith(baseDir + "/")) {
                    return false;
                }
                relative = path.substring(baseDir.length() + 1);
            }
            return pattern.matcher(relative).matches();
        }
    }
}
