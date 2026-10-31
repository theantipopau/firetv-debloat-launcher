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

    /**
     * The day word alone: "tomorrow", "tonight", "today", or empty when the feed names no day.
     * "Tonight" is its own word: a bin put out tonight is not due today, and "Bins today" reads
     * like the truck is already at the kerb.
     */
    static String binsWord(String s) {
        if (s == null) return "";
        String l = s.toLowerCase(Locale.US);
        if (l.contains("tomorrow")) return "tomorrow";
        if (l.contains("tonight")) return "tonight";
        if (l.contains("today")) return "today";
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

    /** Rain that is actually falling, or about to: it keeps its own words and the amber accent. */
    static boolean wetRain(String raw) {
        if (raw == null) return false;
        return raw.startsWith("Raining") || raw.toLowerCase(Locale.US).contains("on the way");
    }

    /**
     * The rain line, from whatever the rain sensors wrote. A dry reading collapses to "No rain expected"
     * only when it carries nothing else: "0.0 mm today - next rain about 2pm" becomes "Next rain about
     * 2pm", because that is the part worth reading on a TV.
     */
    /**
     * The status block's rain line: the headline and the amount, "Rain on the way · 0.8 mm in 3 h".
     * The chance and anything after it stay in the sheet, and "~" is noise at this size.
     */
    static String rainShort(String raw) {
        String[] parts = rainLine(raw).split("\\s*[-\u2013\u2014\u00B7|]\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length && i < 2; i++) {
            String p = parts[i].replace("~", "").trim();
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    static String rainLine(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (wetRain(s)) return s;
        String[] parts = s.split("\\s*[-\u2013\u2014\u00B7|]\\s+", 2);   // hyphen, en/em dash, middle dot
        String tail = parts.length > 1 ? parts[1].trim() : "";
        if (!tail.isEmpty() && tail.toLowerCase(Locale.US).contains("rain")) {
            return Character.toUpperCase(tail.charAt(0)) + tail.substring(1);
        }
        return "No rain expected";
    }

    /**
     * The sheet's weather line, in one sentence: "14 km/h · 62% humidity". Either half can be missing,
     * and the line disappears when Home Assistant sent neither.
     */
    static String weatherLine(String wind, String unit, String humidity) {
        String w = wind == null ? "" : wind.trim();
        String u = unit == null ? "" : unit.trim();
        String h = humidity == null ? "" : humidity.trim();
        String windText = w.isEmpty() ? "" : (u.isEmpty() ? w : w + " " + u);
        String humText = h.isEmpty() ? "" : h + "% humidity";
        if (windText.isEmpty()) return humText;
        return humText.isEmpty() ? windText + " wind" : windText + " \u00B7 " + humText;
    }

    /**
     * "Sunrise 6:12 AM · Sunset 6:12 PM", from epoch seconds, in the TV's own 12/24-hour setting.
     * Empty when neither time is known.
     */
    static String sunLine(long riseAt, long setAt, boolean is24, java.time.ZoneId zone) {
        String rise = riseAt <= 0 ? "" : "Sunrise " + Times.clockTime(riseAt, is24, zone);
        String set = setAt <= 0 ? "" : "Sunset " + Times.clockTime(setAt, is24, zone);
        if (rise.isEmpty()) return set;
        return set.isEmpty() ? rise : rise + " \u00B7 " + set;
    }

    /**
     * Who is in the house, from the `person` entities: "Matt is home", "Matt & Caitlyn are home",
     * "3 people home", "Nobody home". Names are only used while they agree with the count, so a
     * half-written snapshot can never say somebody is in when they are not. Empty when Home
     * Assistant has no person entities at all, so the line disappears rather than guessing.
     */
    static String peopleLine(String people, String names) {
        int home = count(people);
        if (home < 0) return "";
        if (home == 0) return "Nobody home";
        String[] who = home <= 2 && names != null ? names.split("\\s*(?:&|,|\\band\\b)\\s*") : new String[0];
        if (home == 1 && who.length == 1 && !who[0].trim().isEmpty()) return who[0].trim() + " is home";
        if (home == 2 && who.length == 2 && !who[0].trim().isEmpty() && !who[1].trim().isEmpty())
            return who[0].trim() + " & " + who[1].trim() + " are home";
        return home == 1 ? "1 person home" : home + " people home";
    }

    /**
     * The day's solar, and how long the battery can carry the house: "46.7 kWh today · 8 h 38 m left".
     * Either half can be missing; empty when both are.
     */
    static String solarLine(String todayKwh, String left) {
        String today = todayKwh == null ? "" : todayKwh.trim();
        String span = left == null ? "" : left.trim();
        String first = today.isEmpty() ? "" : today + " kWh today";
        String second = span.isEmpty() ? "" : span + " left";
        if (first.isEmpty()) return second.isEmpty() ? "" : "Battery " + second;
        return second.isEmpty() ? first : first + " \u00B7 " + second;
    }

    /**
     * The battery's runway, cut to what a 96dp tile label can hold: "29 h 15 m" becomes "29 h".
     * Minutes are dropped above an hour, because the only decision this number feeds is "is there
     * enough for tonight"; below an hour the minutes are the whole story: "45 m". Callers add the
     * word "left", so the same string can also end a sentence.
     */
    static String runway(String span) {
        if (span == null) return "";
        String s = span.trim();
        if (s.isEmpty()) return "";
        String d = unit(s, "d");
        if (!d.isEmpty()) return d + " d";
        String h = unit(s, "h");
        if (!h.isEmpty() && !h.equals("0")) return Integer.parseInt(h) + " h";
        String m = unit(s, "m");
        if (!m.isEmpty()) return Integer.parseInt(m) + " m";
        return "";
    }

    /** The number before the first {@code unit}, or empty when the span carries no such unit. */
    private static String unit(String s, String unit) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s*" + unit + "\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /** A count from HA, or -1 when the key is missing or is not a number. */
    private static int count(String s) {
        if (s == null) return -1;
        try {
            return Math.max(0, Integer.parseInt(s.trim()));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Dim a glance value once its entity is older than the staleness limit. */
    static float glanceAlpha(long ageSec) {
        return Times.stale(ageSec) ? 0.4f : 1f;
    }
}
