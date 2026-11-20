package com.contextcompresso.vatstash.chunk;

import com.contextcompresso.config.VatStashProperties;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.CompactConstructorDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.Comment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Structure-aware chunker for Java: one outline chunk per type, one chunk per method or
 * constructor (Javadoc and annotations included), runs of tiny members grouped together, and
 * oversized members split into overlapping windows. Falls back to {@link HeuristicChunker} when
 * the source does not parse.
 */
public class JavaParserChunker implements Chunker {

    private static final int TINY_MEMBER_LINES = 5;
    private static final int OUTLINE_LINE_CHARS = 200;

    private final HeuristicChunker fallback;
    private final JavaParser parser;
    private final int maxChunkLines;
    private final int maxChunkChars;

    public JavaParserChunker(VatStashProperties props) {
        this(new HeuristicChunker(props), props.maxChunkLines());
    }

    public JavaParserChunker(HeuristicChunker fallback, int maxChunkLines) {
        this.fallback = fallback;
        this.maxChunkLines = Math.max(10, maxChunkLines);
        this.maxChunkChars = fallback.maxChunkChars();
        ParserConfiguration cfg = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        this.parser = new JavaParser(cfg);
    }

    @Override
    public List<CodeChunk> chunk(String relPath, String language, String content) {
        List<CodeChunk> chunks;
        try {
            ParseResult<CompilationUnit> result = parser.parse(content);
            if (!result.isSuccessful() || result.getResult().isEmpty()
                    || result.getResult().get().getTypes().isEmpty()) {
                return fallback.chunk(relPath, language, content);
            }
            chunks = build(relPath, language, content, result.getResult().get());
        } catch (Exception | StackOverflowError e) {
            return fallback.chunk(relPath, language, content);
        }
        if (chunks.isEmpty()) {
            return fallback.chunk(relPath, language, content);
        }
        chunks.sort(Comparator.comparingInt(CodeChunk::startLine).thenComparingInt(CodeChunk::endLine));
        return chunks;
    }

    private List<CodeChunk> build(String path, String language, String content, CompilationUnit cu) {
        List<String> lines = Lines.split(content);
        List<CodeChunk> out = new ArrayList<>();
        Ctx ctx = new Ctx(path, language, lines, out);

        int headerStart = Integer.MAX_VALUE;
        int headerEnd = -1;
        if (cu.getPackageDeclaration().isPresent()) {
            Range r = cu.getPackageDeclaration().get().getRange().orElse(null);
            if (r != null) {
                headerStart = Math.min(headerStart, r.begin.line);
                headerEnd = Math.max(headerEnd, r.end.line);
            }
        }
        for (ImportDeclaration imp : cu.getImports()) {
            Range r = imp.getRange().orElse(null);
            if (r != null) {
                headerStart = Math.min(headerStart, r.begin.line);
                headerEnd = Math.max(headerEnd, r.end.line);
            }
        }
        if (headerEnd >= headerStart) {
            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse(null);
            emitRange(ctx, pkg, "block", headerStart, headerEnd);
        }
        for (TypeDeclaration<?> type : cu.getTypes()) {
            visitType(ctx, type, null);
        }
        return out;
    }

    private void visitType(Ctx ctx, TypeDeclaration<?> type, String outer) {
        String qualified = outer == null ? type.getNameAsString() : outer + "." + type.getNameAsString();
        int[] range = rangeWithComment(type);
        if (range == null) {
            return;
        }
        emitOutline(ctx, type, qualified, range[0], range[1]);

        List<Member> run = new ArrayList<>();
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof TypeDeclaration<?> nested) {
                flushRun(ctx, run, qualified);
                visitType(ctx, nested, qualified);
                continue;
            }
            int[] r = rangeWithComment(member);
            if (r == null) {
                continue;
            }
            Member m = new Member(member, r[0], r[1], callableKind(member), memberName(member, type));
            int size = r[1] - r[0] + 1;
            if (size < TINY_MEMBER_LINES) {
                if (!run.isEmpty() && r[1] - run.get(0).start + 1 > maxChunkLines) {
                    flushRun(ctx, run, qualified);
                }
                run.add(m);
            } else {
                flushRun(ctx, run, qualified);
                String kind = m.callableKind != null ? m.callableKind : "members";
                String symbol = m.callableKind != null ? qualified + "." + m.name : qualified;
                emitRange(ctx, symbol, kind, r[0], r[1]);
            }
        }
        flushRun(ctx, run, qualified);
    }

    private void flushRun(Ctx ctx, List<Member> run, String qualified) {
        if (run.isEmpty()) {
            return;
        }
        if (run.size() == 1 && run.get(0).callableKind != null) {
            Member m = run.get(0);
            emitRange(ctx, qualified + "." + m.name, m.callableKind, m.start, m.end);
        } else {
            StringBuilder names = new StringBuilder();
            for (Member m : run) {
                if (m.name != null && names.length() < 160) {
                    if (names.length() > 0) {
                        names.append(", ");
                    }
                    names.append(qualified).append('.').append(m.name);
                }
            }
            String symbol = names.length() > 0 ? names.toString() : qualified;
            emitRange(ctx, symbol, "members", run.get(0).start, run.get(run.size() - 1).end);
        }
        run.clear();
    }

    /** Emits one chunk for the 1-based inclusive range, or overlapping windows if it is too long. */
    private void emitRange(Ctx ctx, String symbol, String kind, int startLine, int endLine) {
        int from = Math.max(1, startLine) - 1;
        int to = Math.min(ctx.lines.size(), endLine) - 1;
        if (to < from) {
            return;
        }
        if (to - from + 1 <= maxChunkLines) {
            ctx.out.add(new CodeChunk(ctx.path, ctx.language, symbol, kind, from + 1, to + 1,
                    Lines.join(ctx.lines, from, to, maxChunkChars)));
            return;
        }
        for (int[] w : fallback.windows(from, to)) {
            ctx.out.add(new CodeChunk(ctx.path, ctx.language, symbol, "window", w[0] + 1, w[1] + 1,
                    Lines.join(ctx.lines, w[0], w[1], maxChunkChars)));
        }
    }

    private void emitOutline(Ctx ctx, TypeDeclaration<?> type, String qualified, int startLine, int endLine) {
        List<String> outline = new ArrayList<>();
        outline.add(typeHeader(ctx, type));
        if (type instanceof EnumDeclaration en && !en.getEntries().isEmpty()) {
            StringBuilder sb = new StringBuilder("    ");
            en.getEntries().forEach(e -> sb.append(e.getNameAsString()).append(", "));
            outline.add(clip(sb.toString().replaceAll(", $", ""), OUTLINE_LINE_CHARS));
        }
        for (BodyDeclaration<?> member : type.getMembers()) {
            String line;
            if (member instanceof TypeDeclaration<?> nested) {
                line = "    " + typeHeader(ctx, nested);
            } else if (member instanceof MethodDeclaration md) {
                line = "    " + md.getDeclarationAsString(true, true, true) + ";";
            } else if (member instanceof ConstructorDeclaration cd) {
                line = "    " + cd.getDeclarationAsString(true, true, true) + ";";
            } else if (member instanceof CompactConstructorDeclaration cc) {
                line = "    " + cc.getNameAsString() + " { ... }";
            } else {
                line = "    " + collapsedSource(ctx, member);
            }
            outline.add(clip(line, OUTLINE_LINE_CHARS));
        }
        if (outline.size() > maxChunkLines) {
            int dropped = outline.size() - maxChunkLines;
            outline = new ArrayList<>(outline.subList(0, maxChunkLines));
            outline.add("    // ... " + dropped + " more members");
        }
        StringBuilder sb = new StringBuilder();
        for (String l : outline) {
            sb.append(l).append('\n');
        }
        sb.append('}');
        String text = sb.toString();
        if (text.length() > maxChunkChars) {
            text = text.substring(0, maxChunkChars) + "\n... (outline truncated)";
        }
        ctx.out.add(new CodeChunk(ctx.path, ctx.language, qualified, "type", startLine, endLine, text));
    }

    private String typeHeader(Ctx ctx, TypeDeclaration<?> type) {
        String collapsed = collapsedSource(ctx, type);
        int brace = collapsed.indexOf('{');
        String header = brace >= 0 ? collapsed.substring(0, brace).trim() : collapsed;
        return clip(header + " {", 300);
    }

    private String collapsedSource(Ctx ctx, Node node) {
        Range r = node.getRange().orElse(null);
        if (r == null) {
            return node.getClass().getSimpleName();
        }
        int from = Math.max(1, r.begin.line) - 1;
        int to = Math.min(ctx.lines.size(), r.end.line) - 1;
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to && sb.length() < 400; i++) {
            sb.append(ctx.lines.get(i).trim()).append(' ');
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static int[] rangeWithComment(Node node) {
        Range r = node.getRange().orElse(null);
        if (r == null) {
            return null;
        }
        int start = r.begin.line;
        Comment c = node.getComment().orElse(null);
        if (c != null && c.getRange().isPresent()) {
            start = Math.min(start, c.getRange().get().begin.line);
        }
        return new int[]{start, r.end.line};
    }

    private static String callableKind(BodyDeclaration<?> member) {
        if (member instanceof MethodDeclaration) {
            return "method";
        }
        if (member instanceof ConstructorDeclaration || member instanceof CompactConstructorDeclaration) {
            return "constructor";
        }
        return null;
    }

    private static String memberName(BodyDeclaration<?> member, TypeDeclaration<?> owner) {
        if (member instanceof CallableDeclaration<?> c) {
            return c.getNameAsString();
        }
        if (member instanceof CompactConstructorDeclaration cc) {
            return cc.getNameAsString();
        }
        if (member instanceof com.github.javaparser.ast.body.FieldDeclaration f && !f.getVariables().isEmpty()) {
            return f.getVariable(0).getNameAsString();
        }
        return null;
    }

    private record Ctx(String path, String language, List<String> lines, List<CodeChunk> out) {
    }

    private record Member(BodyDeclaration<?> node, int start, int end, String callableKind, String name) {
    }
}
