package com.contextcompresso.vatstash.index;

import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Emits identifiers and numbers. For each identifier it emits the whole identifier first, then
 * its sub-words (camelCase, PascalCase, snake_case and letter/digit boundaries) at the same
 * position, so a query for {@code user} finds {@code getUserById} and a query for the full
 * identifier still matches exactly.
 */
public final class CodeTokenizer extends Tokenizer {

    private static final int MAX_TOKEN_CHARS = 255;
    private static final int MAX_INPUT_CHARS = 8 * 1024 * 1024;

    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final OffsetAttribute offsetAtt = addAttribute(OffsetAttribute.class);
    private final PositionIncrementAttribute posIncAtt = addAttribute(PositionIncrementAttribute.class);

    private final ArrayDeque<Tok> pending = new ArrayDeque<>();
    private char[] text = new char[0];
    private int length;
    private int pos;
    private boolean loaded;

    private record Tok(String term, int start, int end, int posInc) {
    }

    @Override
    public boolean incrementToken() throws IOException {
        clearAttributes();
        if (!loaded) {
            load();
        }
        while (pending.isEmpty()) {
            if (!scanNext()) {
                return false;
            }
        }
        Tok t = pending.poll();
        termAtt.setEmpty().append(t.term);
        offsetAtt.setOffset(correctOffset(t.start), correctOffset(t.end));
        posIncAtt.setPositionIncrement(t.posInc);
        return true;
    }

    @Override
    public void end() throws IOException {
        super.end();
        int finalOffset = correctOffset(length);
        offsetAtt.setOffset(finalOffset, finalOffset);
    }

    @Override
    public void reset() throws IOException {
        super.reset();
        pending.clear();
        loaded = false;
        length = 0;
        pos = 0;
    }

    private void load() throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int r;
        while (sb.length() < MAX_INPUT_CHARS && (r = input.read(buf)) != -1) {
            sb.append(buf, 0, r);
        }
        text = new char[sb.length()];
        sb.getChars(0, sb.length(), text, 0);
        length = text.length;
        pos = 0;
        loaded = true;
    }

    private boolean scanNext() {
        while (pos < length && !isIdentStart(text[pos]) && !isDigit(text[pos])) {
            pos++;
        }
        if (pos >= length) {
            return false;
        }
        int start = pos;
        if (isDigit(text[pos])) {
            while (pos < length && isDigit(text[pos])) {
                pos++;
            }
            if (pos - start <= MAX_TOKEN_CHARS) {
                pending.add(new Tok(new String(text, start, pos - start), start, pos, 1));
            }
            return true;
        }
        while (pos < length && isIdentPart(text[pos])) {
            pos++;
        }
        int len = pos - start;
        if (len > MAX_TOKEN_CHARS) {
            return true;
        }
        String whole = new String(text, start, len);
        pending.add(new Tok(whole, start, pos, 1));
        List<int[]> parts = split(whole);
        boolean single = parts.size() == 1 && parts.get(0)[0] == 0 && parts.get(0)[1] == len;
        if (!single) {
            for (int[] p : parts) {
                pending.add(new Tok(whole.substring(p[0], p[1]), start + p[0], start + p[1], 0));
            }
        }
        return true;
    }

    /** Sub-word ranges [from, to) of an identifier. */
    static List<int[]> split(String ident) {
        List<int[]> parts = new ArrayList<>();
        int n = ident.length();
        int segStart = -1;
        for (int i = 0; i <= n; i++) {
            boolean sep = i == n || ident.charAt(i) == '_' || ident.charAt(i) == '$';
            if (sep) {
                if (segStart >= 0) {
                    splitCamel(ident, segStart, i, parts);
                    segStart = -1;
                }
            } else if (segStart < 0) {
                segStart = i;
            }
        }
        return parts;
    }

    private static void splitCamel(String s, int from, int to, List<int[]> out) {
        int partStart = from;
        for (int i = from + 1; i < to; i++) {
            char prev = s.charAt(i - 1);
            char cur = s.charAt(i);
            boolean boundary = false;
            if (Character.isLowerCase(prev) && Character.isUpperCase(cur)) {
                boundary = true;
            } else if (Character.isUpperCase(prev) && Character.isUpperCase(cur) && i + 1 < to
                    && Character.isLowerCase(s.charAt(i + 1))) {
                boundary = true;
            } else if (Character.isDigit(prev) != Character.isDigit(cur)) {
                boundary = true;
            }
            if (boundary) {
                out.add(new int[]{partStart, i});
                partStart = i;
            }
        }
        out.add(new int[]{partStart, to});
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
