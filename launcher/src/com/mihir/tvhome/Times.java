package com.mihir.tvhome;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Pure text and time helpers. Deliberately free of Android types so tests/ can run them on a
 * plain JVM - the Fire OS build has no test runner, and a rule about time zones is exactly the
 * kind of thing that is wrong until something checks it.
 */
final class Times {
    private Times() { }

    /**
     * A calendar line arrives as free text from Home Assistant, often with codes in brackets:
     * "Training [BCE10100] 2026 TERM 4" -> "Training 2026 TERM 4".
     */
    static String cleanCalendar(String s) {
        if (s == null) return "";
        return s.replaceAll("\\[[^\\]]*\\]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * How far away something is, in words: "in 2 h", "in 25 min", "now", "12 min ago".
     * Empty when there is no time to compare with.
     */
    static String relative(long atSec, long nowSec) {
        if (atSec <= 0) return "";
        long mins = Math.round((atSec - nowSec) / 60.0);
        if (mins > -1 && mins < 1) return "now";
        long abs = Math.abs(mins);
        String span;
        if (abs < 60) span = abs + " min";
        else if (abs < 24 * 60) span = (abs / 60) + " h";
        else span = (abs / (24 * 60)) + " d";
        return mins < 0 ? span + " ago" : "in " + span;
    }

    /** The system's 12/24-hour setting decides the pattern; never assume a country's convention. */
    static String clockTime(long epochSec, boolean is24, ZoneId zone) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern(is24 ? "HH:mm" : "h:mm a", Locale.UK);
        String s = ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSec), zone).format(f);
        return is24 ? s : s.toUpperCase(Locale.UK);   // "Ends 10:00 AM", matching the clock's own AM/PM
    }

    /** "H:mm" or "h:mm" for the big clock, which draws its own AM/PM beside it. */
    static String clockDigits(long epochSec, boolean is24, ZoneId zone) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern(is24 ? "HH:mm" : "h:mm", Locale.UK);
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSec), zone).format(f);
    }

    /**
     * One decimal place for anything measured in kW: an inverter reports 2.708 and a screen that
     * shows every digit looks like a debug readout, not a home screen.
     */
    static String kw(String raw) {
        try {
            return String.format(Locale.UK, "%.1f", Double.parseDouble(raw.trim()));
        } catch (RuntimeException e) {
            return raw == null ? "" : raw.trim();
        }
    }

    /** Whether a value is stale enough to be shown dimmed: 15 minutes since its entity changed. */
    static boolean stale(long ageSec) {
        return ageSec > 15 * 60;
    }

    /**
     * "42m ago", for the label under a stale tile. The dimmed tile already says the value is stale,
     * and a tile column is about 87dp wide at the 18sp floor - "updated 42 min ago" measures 171dp,
     * so this keeps the age and drops the word.
     */
    static String updatedAgo(long ageSec) {
        long mins = Math.max(1, ageSec / 60);
        return mins < 60 ? mins + "m ago" : (mins / 60) + "h ago";
    }
}
