package com.mihir.tvhome;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure text and time helpers. Deliberately free of Android types so tests/ can run them on a
 * plain JVM - the Fire OS build has no test runner, and a rule about time zones is exactly the
 * kind of thing that is wrong until something checks it.
 *
 * Every clock on every surface comes from here: one set of patterns, so the top-bar clock, a
 * hero's "Ends", the calendar line and the screensaver cannot drift apart.
 */
final class Times {
    private Times() { }

    private static final DateTimeFormatter H24 = DateTimeFormatter.ofPattern("HH:mm", Locale.UK);
    private static final DateTimeFormatter H12 = DateTimeFormatter.ofPattern("h:mm", Locale.UK);
    private static final DateTimeFormatter MERIDIEM = DateTimeFormatter.ofPattern("a", Locale.UK);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK);

    /** A wall-clock time typed into free text, e.g. "12:00pm" or "12:00 pm". */
    private static final Pattern TYPED_TIME = Pattern.compile("(?i)\\b(\\d{1,2}):(\\d{2})(?:\\s*([ap])\\.?m\\.?)?\\b");

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

    private static ZonedDateTime at(long epochSec, ZoneId zone) {
        return ZonedDateTime.ofInstant(Instant.ofEpochSecond(epochSec), zone);
    }

    /** "9:32" or "09:32": the digits alone, for a clock that sets its meridian in its own view. */
    static String clockDigits(long epochSec, boolean is24, ZoneId zone) {
        return at(epochSec, zone).format(is24 ? H24 : H12);
    }

    /** "AM"/"PM" in 12-hour mode, empty in 24-hour mode. Upper case: the house style, everywhere. */
    static String meridian(long epochSec, boolean is24, ZoneId zone) {
        return is24 ? "" : at(epochSec, zone).format(MERIDIEM).toUpperCase(Locale.UK);
    }

    /** "Wednesday 29 October": the day and month, never the year - a home screen is not a form. */
    static String dateLine(long epochSec, ZoneId zone) {
        return at(epochSec, zone).format(DATE);
    }

    /**
     * The greeting above the clock: morning from 5, afternoon from noon, evening from 18. The small
     * hours stay "Good evening" - a home screen should never wish anyone a good morning at 2 am.
     */
    static String greeting(long epochSec, ZoneId zone) {
        int h = at(epochSec, zone).getHour();
        if (h >= 5 && h < 12) return "Good morning";
        if (h >= 12 && h < 18) return "Good afternoon";
        return "Good evening";
    }

    /** The whole clock: "9:32 AM" or "09:32", built from the same two pieces the big clocks use. */
    static String clockTime(long epochSec, boolean is24, ZoneId zone) {
        String digits = clockDigits(epochSec, is24, zone);
        return is24 ? digits : digits + " " + meridian(epochSec, is24, zone);
    }

    /**
     * Rewrites a time typed into free text into the house format, following the device's 12/24-hour
     * setting: "Next: 12:00pm Working" -> "12:00 PM Working". A bare "12:00" is left alone - without
     * a meridian there is no way to know whether it means noon or midnight, and guessing is worse
     * than echoing.
     */
    static String wallClock(String text, boolean is24) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = TYPED_TIME.matcher(text);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            String ap = m.group(3);
            if (ap == null) {
                out.append(m.group());
            } else {
                int h = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                if (h < 1 || h > 12 || min > 59) {
                    out.append(m.group());
                } else {
                    LocalTime t = LocalTime.of((h % 12) + ("p".equalsIgnoreCase(ap) ? 12 : 0), min);
                    out.append(is24 ? t.format(H24)
                            : t.format(H12) + " " + t.format(MERIDIEM).toUpperCase(Locale.UK));
                }
            }
            last = m.end();
        }
        return out.append(text.substring(last)).toString();
    }

    /**
     * One line for the next calendar event: codes stripped, its typed-in time in the house format,
     * the redundant "Next:" dropped, and the distance in words after a separator. The sheet gives
     * this a single line, so the shorter it is the more of the event survives.
     */
    static String calendarLine(String raw, boolean is24, long atSec, long nowSec) {
        String text = wallClock(cleanCalendar(raw), is24).replaceFirst("(?i)^next:?\\s*", "");
        String rel = relative(atSec, nowSec);
        if (rel.isEmpty()) return text;
        return text.isEmpty() ? rel : text + " \u00B7 " + rel;
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
