package com.contextcompresso.compression;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips comments from code blocks (markdown fenced blocks or content-block type=code),
 * with string-literal awareness so "//" or "#" inside a quoted string is never treated
 * as a comment start.
 */
@Component
public class CodeCompressor {

    private static final Set<String> HASH_COMMENT_LANGS = Set.of("python");
    private static final Set<String> SLASH_COMMENT_LANGS = Set.of(
            "java", "javascript", "typescript", "go", "rust", "c", "cpp", "cs", "kotlin", "scala", "swift");
    private static final Set<String> KNOWN_LANGS;
    static {
        KNOWN_LANGS = new java.util.HashSet<>();
        KNOWN_LANGS.addAll(HASH_COMMENT_LANGS);
        KNOWN_LANGS.addAll(SLASH_COMMENT_LANGS);
    }

    private static final Pattern FENCE_PATTERN = Pattern.compile(
            "```([a-zA-Z0-9_+-]*)\\n([\\s\\S]*?)```", Pattern.MULTILINE);

    private static final Pattern BLANK_LINE_RUN = Pattern.compile("\\n{3,}");
    private static final Pattern IMPORT_LINE = Pattern.compile(
            "(?m)^\\s*(import\\s+.*|#include\\s+.*|using\\s+[A-Za-z0-9_.]+;)\\s*$\\n?");

    public String compressMarkdown(String markdown, boolean aggressiveMode) {
        if (markdown == null || markdown.isEmpty() || !markdown.contains("```")) {
            return markdown;
        }
        Matcher matcher = FENCE_PATTERN.matcher(markdown);
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            result.append(markdown, lastEnd, matcher.start());
            String lang = matcher.group(1) == null ? "" : matcher.group(1).toLowerCase();
            String code = matcher.group(2);
            String compressed = compressCode(code, lang, aggressiveMode);
            result.append("```").append(matcher.group(1) == null ? "" : matcher.group(1)).append("\n");
            result.append(compressed);
            if (!compressed.endsWith("\n")) {
                result.append("\n");
            }
            result.append("```");
            lastEnd = matcher.end();
        }
        result.append(markdown.substring(lastEnd));
        return result.toString();
    }

    public String compressCode(String code, String language, boolean aggressiveMode) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        String lang = language == null ? "" : language.toLowerCase();
        String working = code;

        if (KNOWN_LANGS.contains(lang)) {
            boolean hashStyle = HASH_COMMENT_LANGS.contains(lang);
            working = stripComments(working, hashStyle);
            if (aggressiveMode) {
                working = IMPORT_LINE.matcher(working).replaceAll("");
            }
        }
        working = collapseBlankLines(working);
        return working;
    }

    private String collapseBlankLines(String code) {
        return BLANK_LINE_RUN.matcher(code).replaceAll("\n\n");
    }

    /**
     * Strips "//" and "/* *\/" (or "#") comments while respecting string/char literals,
     * so patterns like "http://example.com" or a "#" inside a string are left alone.
     */
    private String stripComments(String code, boolean hashStyle) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        int n = code.length();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean inBacktick = false;

        while (i < n) {
            char c = code.charAt(i);
            boolean inString = inSingleQuote || inDoubleQuote || inBacktick;

            if (inString) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    out.append(code.charAt(i + 1));
                    i += 2;
                    continue;
                }
                if (inSingleQuote && c == '\'') inSingleQuote = false;
                else if (inDoubleQuote && c == '"') inDoubleQuote = false;
                else if (inBacktick && c == '`') inBacktick = false;
                i++;
                continue;
            }

            if (c == '\'') {
                inSingleQuote = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '"') {
                inDoubleQuote = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '`') {
                inBacktick = true;
                out.append(c);
                i++;
                continue;
            }

            if (hashStyle && c == '#') {
                i = skipToEndOfLine(code, i);
                continue;
            }

            if (!hashStyle && c == '/' && i + 1 < n && code.charAt(i + 1) == '/') {
                i = skipToEndOfLine(code, i);
                continue;
            }

            if (!hashStyle && c == '/' && i + 1 < n && code.charAt(i + 1) == '*') {
                int end = code.indexOf("*/", i + 2);
                i = (end == -1) ? n : end + 2;
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    private int skipToEndOfLine(String code, int start) {
        int idx = code.indexOf('\n', start);
        return idx == -1 ? code.length() : idx;
    }

    public boolean isKnownLanguage(String language) {
        return language != null && KNOWN_LANGS.contains(language.toLowerCase());
    }
}
