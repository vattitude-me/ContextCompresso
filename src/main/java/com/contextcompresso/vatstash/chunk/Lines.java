package com.contextcompresso.vatstash.chunk;

import java.util.ArrayList;
import java.util.List;

/** Line splitting shared by the chunkers so that line numbers agree with JavaParser's. */
final class Lines {

    private Lines() {
    }

    /** Splits on \n, \r\n and \r. A trailing terminator does not create an extra empty line. */
    static List<String> split(String content) {
        List<String> lines = new ArrayList<>();
        int n = content.length();
        int start = 0;
        for (int i = 0; i < n; i++) {
            char c = content.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(content.substring(start, i));
                if (c == '\r' && i + 1 < n && content.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < n) {
            lines.add(content.substring(start));
        }
        return lines;
    }

    /** Joins lines [from, to] (0-based inclusive), truncating any single line longer than maxLineChars. */
    static String join(List<String> lines, int from, int to, int maxLineChars) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            if (i > from) {
                sb.append('\n');
            }
            String line = lines.get(i);
            if (line.length() > maxLineChars) {
                sb.append(line, 0, maxLineChars).append(" ... (line truncated)");
            } else {
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
