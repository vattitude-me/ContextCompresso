package com.contextcompresso.vatstash.chunk;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Maps file names to language ids and rejects files that are never worth indexing. */
@Component
public class LanguageDetector {

    static final long MAX_JSON_BYTES = 100L * 1024L;

    private static final Map<String, String> BY_EXTENSION = new HashMap<>();
    private static final Map<String, String> BY_FILENAME = new HashMap<>();
    private static final Map<String, String> ALIASES = new HashMap<>();
    private static final Set<String> DENIED_NAMES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml");

    static {
        ext("java", "java");
        ext("kotlin", "kt", "kts");
        ext("scala", "scala");
        ext("groovy", "groovy");
        ext("gradle", "gradle");
        ext("typescript", "ts", "tsx");
        ext("javascript", "js", "jsx", "mjs", "cjs");
        ext("vue", "vue");
        ext("svelte", "svelte");
        ext("python", "py");
        ext("go", "go");
        ext("rust", "rs");
        ext("c", "c", "h");
        ext("cpp", "cc", "cpp", "hpp");
        ext("csharp", "cs");
        ext("ruby", "rb");
        ext("php", "php");
        ext("swift", "swift");
        ext("sql", "sql");
        ext("shell", "sh", "bash");
        ext("powershell", "ps1");
        ext("yaml", "yaml", "yml");
        ext("json", "json");
        ext("xml", "xml");
        ext("properties", "properties");
        ext("toml", "toml");
        ext("ini", "ini");
        ext("markdown", "md");
        ext("html", "html");
        ext("css", "css");
        ext("scss", "scss");
        ext("proto", "proto");
        ext("graphql", "graphql");
        ext("terraform", "tf");

        BY_FILENAME.put("dockerfile", "dockerfile");
        BY_FILENAME.put("makefile", "makefile");
        BY_FILENAME.put("jenkinsfile", "jenkinsfile");

        // Short forms accepted in search filters.
        ALIASES.put("ts", "typescript");
        ALIASES.put("tsx", "typescript");
        ALIASES.put("js", "javascript");
        ALIASES.put("jsx", "javascript");
        ALIASES.put("py", "python");
        ALIASES.put("kt", "kotlin");
        ALIASES.put("rs", "rust");
        ALIASES.put("cs", "csharp");
        ALIASES.put("c#", "csharp");
        ALIASES.put("c++", "cpp");
        ALIASES.put("rb", "ruby");
        ALIASES.put("sh", "shell");
        ALIASES.put("bash", "shell");
        ALIASES.put("yml", "yaml");
        ALIASES.put("md", "markdown");
        ALIASES.put("tf", "terraform");
    }

    private static void ext(String language, String... extensions) {
        for (String e : extensions) {
            BY_EXTENSION.put(e, language);
        }
    }

    /** Normalises a user-supplied language filter to the ids this detector produces. */
    public static String normalizeLanguage(String language) {
        if (language == null) {
            return "";
        }
        String l = language.trim().toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(l, l);
    }

    public Optional<String> detect(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return Optional.empty();
        }
        long size = -1;
        String lower = fileName.toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json")) {
            try {
                size = Files.size(path);
            } catch (IOException e) {
                size = -1;
            }
        }
        return detect(fileName.toString(), size);
    }

    /** {@code size} may be -1 when unknown; it is only consulted for .json files. */
    public Optional<String> detect(String fileName, long size) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (DENIED_NAMES.contains(lower) || lower.endsWith(".min.js") || lower.endsWith(".min.css")
                || lower.endsWith(".map")) {
            return Optional.empty();
        }
        String byName = BY_FILENAME.get(lower);
        if (byName == null && lower.startsWith("dockerfile.")) {
            byName = "dockerfile";
        }
        if (byName != null) {
            return Optional.of(byName);
        }
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return Optional.empty();
        }
        String language = BY_EXTENSION.get(lower.substring(dot + 1));
        if (language == null) {
            return Optional.empty();
        }
        if ("json".equals(language) && size > MAX_JSON_BYTES) {
            return Optional.empty();
        }
        return Optional.of(language);
    }
}
