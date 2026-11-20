package com.contextcompresso.vatstash.chunk;

import com.contextcompresso.config.VatStashProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Language-neutral chunker. Candidate boundaries are top-level declarations (brace depth 0 for
 * C-like languages, indent 0 for Python-like and YAML-like ones, headings for Markdown). A chunk
 * is cut at a boundary once it holds at least {@code minChunkLines}; it is force-cut at
 * {@code maxChunkLines} or {@code maxChunkChars}, preferably after a blank line, with overlap.
 * Every line of the file ends up in at least one chunk.
 */
public class HeuristicChunker implements Chunker {

    private enum Style { BRACE, PYTHON, INDENT0, MARKDOWN }

    private static final int BLANK_SEARCH_WINDOW = 20;
    private static final Set<String> NOT_SYMBOLS = Set.of(
            "if", "for", "while", "switch", "catch", "return", "function", "def", "class", "else", "do", "try",
            "new", "throw", "await", "synchronized");
    private static final Pattern MODIFIERS = Pattern.compile(
            "^(?:(?:export|default|async|public|private|protected|static|final|abstract|pub|declare|open|"
                    + "internal|sealed|data|override|suspend|inline|unsafe|extern|readonly|partial)\\s+)+");
    private static final Pattern KEYWORD_SYMBOL = Pattern.compile(
            "^(?:class|interface|enum|struct|trait|fn|func|fun|function|def|type|const|let|var|val|object|impl|"
                    + "module|namespace|record|message|service)\\s+([A-Za-z_$][\\w$]*)");
    private static final Pattern GO_METHOD = Pattern.compile("^func\\s*\\([^)]*\\)\\s*([A-Za-z_$][\\w$]*)");
    private static final Pattern CALL_LIKE = Pattern.compile("([A-Za-z_$][\\w$]*)\\s*\\(");
    private static final Pattern PY_BOUNDARY = Pattern.compile("^(?:async\\s+def\\s|def\\s|class\\s|module\\s|@)");

    private final int minChunkLines;
    private final int maxChunkLines;
    private final int overlapLines;
    private final int maxChunkChars;

    public HeuristicChunker(VatStashProperties props) {
        this(props.minChunkLines(), props.maxChunkLines(), props.overlapLines(), props.maxChunkChars());
    }

    public HeuristicChunker(int minChunkLines, int maxChunkLines, int overlapLines, int maxChunkChars) {
        this.minChunkLines = Math.max(1, minChunkLines);
        this.maxChunkLines = Math.max(this.minChunkLines, maxChunkLines);
        this.overlapLines = Math.max(0, Math.min(overlapLines, this.maxChunkLines / 2));
        this.maxChunkChars = Math.max(200, maxChunkChars);
    }

    @Override
    public List<CodeChunk> chunk(String relPath, String language, String content) {
        List<String> lines = Lines.split(content);
        int n = lines.size();
        List<CodeChunk> out = new ArrayList<>();
        if (n == 0) {
            return out;
        }
        Style style = styleFor(language);
        boolean[] boundary = boundaries(lines, style);

        int start = 0;
        boolean startedByForcedCut = false;
        while (start < n) {
            int chars = lines.get(start).length() + 1;
            int i = start + 1;
            boolean forced = false;
            for (; i < n; i++) {
                int count = i - start;
                if (boundary[i] && count >= minChunkLines) {
                    break;
                }
                int lineChars = lines.get(i).length() + 1;
                if (count >= maxChunkLines || chars + lineChars > maxChunkChars) {
                    forced = true;
                    break;
                }
                chars += lineChars;
            }
            int end = i - 1;
            if (forced) {
                int lowest = Math.max(start + 1, end - BLANK_SEARCH_WINDOW + 1);
                for (int j = end; j >= lowest; j--) {
                    if (lines.get(j).isBlank()) {
                        end = j;
                        break;
                    }
                }
            }
            boolean wholeFile = start == 0 && end == n - 1;
            String kind;
            if (wholeFile) {
                kind = "file";
            } else if (forced || startedByForcedCut) {
                kind = "window";
            } else if (style == Style.MARKDOWN) {
                kind = "section";
            } else {
                kind = "block";
            }
            out.add(new CodeChunk(relPath, language, symbolFor(lines, start, end, style), kind,
                    start + 1, end + 1, Lines.join(lines, start, end, maxChunkChars)));
            if (end >= n - 1) {
                break;
            }
            if (forced) {
                start = Math.max(end + 1 - overlapLines, start + 1);
                startedByForcedCut = true;
            } else {
                start = end + 1;
                startedByForcedCut = false;
            }
        }
        return out;
    }

    /**
     * Splits the 0-based inclusive range [from, to] into windows of at most maxChunkLines lines
     * with overlapLines of overlap. Returns pairs {from, to}.
     */
    List<int[]> windows(int from, int to) {
        List<int[]> result = new ArrayList<>();
        int s = from;
        while (true) {
            int e = Math.min(to, s + maxChunkLines - 1);
            result.add(new int[]{s, e});
            if (e >= to) {
                break;
            }
            s = Math.max(e + 1 - overlapLines, s + 1);
        }
        return result;
    }

    int maxChunkChars() {
        return maxChunkChars;
    }

    // ---- boundaries ----

    private static Style styleFor(String language) {
        if (language == null) {
            return Style.BRACE;
        }
        return switch (language) {
            case "markdown" -> Style.MARKDOWN;
            case "python", "ruby" -> Style.PYTHON;
            case "yaml", "toml", "ini", "properties", "sql", "shell", "powershell", "makefile", "dockerfile",
                    "terraform", "graphql", "proto", "xml", "html" -> Style.INDENT0;
            default -> Style.BRACE;
        };
    }

    private boolean[] boundaries(List<String> lines, Style style) {
        int n = lines.size();
        boolean[] b = new boolean[n];
        switch (style) {
            case MARKDOWN -> {
                boolean inFence = false;
                for (int i = 0; i < n; i++) {
                    String l = lines.get(i);
                    if (l.startsWith("```") || l.startsWith("~~~")) {
                        inFence = !inFence;
                    } else if (!inFence && l.startsWith("#")) {
                        b[i] = true;
                    }
                }
            }
            case PYTHON -> {
                String prevNonBlank = null;
                for (int i = 0; i < n; i++) {
                    String l = lines.get(i);
                    if (!l.isBlank()) {
                        boolean startsDecl = !Character.isWhitespace(l.charAt(0)) && PY_BOUNDARY.matcher(l).find();
                        boolean afterDecorator = prevNonBlank != null && prevNonBlank.startsWith("@");
                        b[i] = startsDecl && !afterDecorator;
                        prevNonBlank = l;
                    }
                }
            }
            case INDENT0 -> {
                String prevNonBlank = null;
                for (int i = 0; i < n; i++) {
                    String l = lines.get(i);
                    if (!l.isBlank()) {
                        boolean indent0 = !Character.isWhitespace(l.charAt(0));
                        boolean afterComment = prevNonBlank != null && isHashComment(prevNonBlank.trim());
                        b[i] = indent0 && !afterComment;
                        prevNonBlank = l;
                    }
                }
            }
            default -> braceBoundaries(lines, b);
        }
        return b;
    }

    private static boolean isHashComment(String trimmed) {
        return trimmed.startsWith("#") || trimmed.startsWith("--") || trimmed.startsWith(";");
    }

    private static void braceBoundaries(List<String> lines, boolean[] b) {
        int depth = 0;
        boolean inBlockComment = false;
        boolean inBacktick = false;
        String prevNonBlank = null;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            boolean blank = l.isBlank();
            if (!blank) {
                String t = l.trim();
                boolean afterDocOrAnnotation = prevNonBlank != null
                        && (prevNonBlank.startsWith("//") || prevNonBlank.startsWith("/*")
                        || prevNonBlank.startsWith("*") || prevNonBlank.startsWith("@"));
                b[i] = depth == 0 && !inBlockComment && !inBacktick && !afterDocOrAnnotation;
                prevNonBlank = t;
            }
            // advance depth over this line, ignoring braces in comments and simple strings
            char quote = 0;
            for (int k = 0; k < l.length(); k++) {
                char c = l.charAt(k);
                char next = k + 1 < l.length() ? l.charAt(k + 1) : 0;
                if (inBlockComment) {
                    if (c == '*' && next == '/') {
                        inBlockComment = false;
                        k++;
                    }
                } else if (inBacktick) {
                    if (c == '\\') {
                        k++;
                    } else if (c == '`') {
                        inBacktick = false;
                    }
                } else if (quote != 0) {
                    if (c == '\\') {
                        k++;
                    } else if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '/' && next == '/') {
                    break;
                } else if (c == '/' && next == '*') {
                    inBlockComment = true;
                    k++;
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '`') {
                    inBacktick = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth = Math.max(0, depth - 1);
                }
            }
        }
    }

    // ---- symbols ----

    private static String symbolFor(List<String> lines, int start, int end, Style style) {
        if (style == Style.MARKDOWN) {
            for (int i = start; i <= end; i++) {
                String l = lines.get(i);
                if (l.startsWith("#")) {
                    String heading = l.replaceFirst("^#+\\s*", "").trim();
                    return heading.isEmpty() ? null : heading;
                }
                if (!l.isBlank()) {
                    return null;
                }
            }
            return null;
        }
        for (int i = start; i <= end; i++) {
            String t = lines.get(i).trim();
            if (t.isEmpty() || t.startsWith("//") || t.startsWith("/*") || t.startsWith("*") || t.startsWith("#")
                    || t.startsWith("@")) {
                continue;
            }
            return symbolFromLine(t);
        }
        return null;
    }

    static String symbolFromLine(String trimmed) {
        Matcher go = GO_METHOD.matcher(trimmed);
        if (go.find()) {
            return go.group(1);
        }
        String stripped = MODIFIERS.matcher(trimmed).replaceFirst("");
        Matcher kw = KEYWORD_SYMBOL.matcher(stripped);
        if (kw.find()) {
            return kw.group(1);
        }
        Matcher call = CALL_LIKE.matcher(stripped);
        while (call.find()) {
            String name = call.group(1);
            if (!NOT_SYMBOLS.contains(name)) {
                return name;
            }
        }
        return null;
    }
}
