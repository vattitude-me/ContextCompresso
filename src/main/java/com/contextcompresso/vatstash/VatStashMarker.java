package com.contextcompresso.vatstash;

/**
 * Every VatStash result text starts with this marker. The compression pipeline leaves any text
 * carrying it untouched: the result is already token-budgeted, and truncating it would throw away
 * the snippets the model asked for.
 */
public final class VatStashMarker {

    public static final String MARKER = "[vatstash v1]";
    private static final int WINDOW = 200;

    private VatStashMarker() {
    }

    public static boolean isVatStashText(String s) {
        if (s == null || s.length() < MARKER.length()) {
            return false;
        }
        int limit = Math.min(s.length(), WINDOW);
        return s.substring(0, limit).contains(MARKER);
    }
}
