package com.mihir.tvhome;

import java.util.Locale;

/**
 * Rules the cards follow, kept free of Android so tests/ can hold them still: which artwork a
 * Continue Watching tile may use, how bins are said in one short line, and when a glance value is
 * too old to look live.
 */
final class Cards {
    static final int NONE = 0, IMAGE = 1, LOGO = 2;

    private Cards() { }

    /**
     * A resume tile shows the 16:9 still the feed already transcoded; only when a title has no
     * still at all does its clearlogo stand in. A portrait poster is never stretched to fill this
     * shape - the feed does not carry one, and cropping it would put a sliver of a face on screen.
     */
    static int art(String image, String logo) {
        if (image != null && !image.isEmpty()) return IMAGE;
        if (logo != null && !logo.isEmpty()) return LOGO;
        return NONE;
    }

    /** "General bins - out tonight" -> "General". */
    static String binsKind(String s) {
        if (s == null || s.isEmpty()) return "";
        String head = s.split("[-\u2013]")[0].replaceAll("(?i)\\s*bins?\\s*", " ").trim();
        return head.isEmpty() ? "Bins" : head;
    }

    /** The day word alone: "tomorrow", "today", or empty when the feed names no day. */
    static String binsWord(String s) {
        if (s == null) return "";
        String l = s.toLowerCase(Locale.US);
        if (l.contains("tomorrow")) return "tomorrow";
        if (l.contains("tonight") || l.contains("today")) return "today";
        return "";
    }

    /** The strip's one-line version: "Bins tomorrow", "Bins today", or empty when not due. */
    static String binsGlance(String bins) {
        String word = binsWord(bins);
        return word.isEmpty() ? "" : "Bins " + word;
    }

    /** The tile's one-line version of the day, capitalised; empty when no day is known. */
    static String binsDay(String bins) {
        String word = binsWord(bins);
        return word.isEmpty() ? "" : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    /** Dim a glance value once its entity is older than the staleness limit. */
    static float glanceAlpha(long ageSec) {
        return Times.stale(ageSec) ? 0.4f : 1f;
    }
}
