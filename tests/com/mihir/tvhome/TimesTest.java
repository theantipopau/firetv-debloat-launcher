package com.mihir.tvhome;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Plain JVM tests for the rules that are easy to get wrong and impossible to eyeball: what time a
 * programme ends on the TV when the guide is written in another country, what a calendar line looks
 * like after its codes are stripped, and when a value is too old to trust.
 *
 * No JUnit and no Gradle: the launcher is built with raw SDK tools, so this is a main() that exits
 * non-zero on the first failure. Run it with tests/run.sh.
 */
public final class TimesTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        ZoneId brisbane = ZoneId.of("Australia/Brisbane");

        // A New Zealand guide entry: 07:00 NZDT (UTC+13) is 18:00Z the day before, which is
        // 04:00 in Brisbane (UTC+10). The TV must show its own clock, not the guide's.
        long nz = OffsetDateTime.parse("2026-10-29T07:00:00+13:00").toEpochSecond();
        expect("NZ end, 12h", "4:00 AM", Times.clockTime(nz, false, brisbane));
        expect("NZ end, 24h", "04:00", Times.clockTime(nz, true, brisbane));

        // A UK guide entry after the DST change: 21:35Z is 07:35 the next morning in Brisbane.
        long uk = OffsetDateTime.parse("2026-10-29T21:35:00Z").toEpochSecond();
        expect("UK end, 12h", "7:35 AM", Times.clockTime(uk, false, brisbane));
        expect("UK end, 24h", "07:35", Times.clockTime(uk, true, brisbane));

        // The same instant in the guide's own zone: proof the conversion is a zone change, not a
        // constant offset baked in.
        expect("NZ end, same zone", "7:00 AM", Times.clockTime(nz, false, ZoneId.of("Pacific/Auckland")));

        // One formatter for the clock and every "Ends" line. 12h has no leading zero, and the
        // two edges of the dial (midnight, noon) are where hand-written formatting usually breaks.
        long midnight = OffsetDateTime.parse("2026-10-29T14:30:00Z").toEpochSecond();   // 00:30 Brisbane
        expect("midnight 12h", "12:30 AM", Times.clockTime(midnight, false, brisbane));
        long noon = OffsetDateTime.parse("2026-10-30T02:05:00Z").toEpochSecond();       // 12:05 Brisbane
        expect("noon 12h", "12:05 PM", Times.clockTime(noon, false, brisbane));
        expect("no leading zero", "4:00 AM", Times.clockTime(nz, false, brisbane));
        expect("midnight 24h", "00:30", Times.clockTime(midnight, true, brisbane));

        // Every clock is built from the same digits + meridian pair, so a surface that draws the
        // meridian itself cannot disagree with the top bar.
        expect("digits 12h", "4:00", Times.clockDigits(nz, false, brisbane));
        expect("meridian 12h", "AM", Times.meridian(nz, false, brisbane));
        expect("meridian 24h", "", Times.meridian(nz, true, brisbane));
        expect("clock is digits + meridian", "4:00 AM",
                Times.clockDigits(nz, false, brisbane) + " " + Times.meridian(nz, false, brisbane));
        expect("digits 24h", "04:00", Times.clockDigits(nz, true, brisbane));

        // The date under the clock: day, then day of the month, then month. No year, no leading zero.
        expect("date line", "Thursday 29 October", Times.dateLine(
                OffsetDateTime.parse("2026-10-29T09:00:00+10:00").toEpochSecond(), brisbane));
        expect("date line, one digit day", "Sunday 1 March", Times.dateLine(
                OffsetDateTime.parse("2026-03-01T09:00:00+10:00").toEpochSecond(), brisbane));

        // The greeting follows the device clock; the small hours are still the evening.
        expect("greeting 04:00", "Good evening",
                Times.greeting(OffsetDateTime.parse("2026-10-29T04:00:00+10:00").toEpochSecond(), brisbane));
        expect("greeting 05:00", "Good morning",
                Times.greeting(OffsetDateTime.parse("2026-10-29T05:00:00+10:00").toEpochSecond(), brisbane));
        expect("greeting 11:59", "Good morning",
                Times.greeting(OffsetDateTime.parse("2026-10-29T11:59:00+10:00").toEpochSecond(), brisbane));
        expect("greeting 12:00", "Good afternoon",
                Times.greeting(OffsetDateTime.parse("2026-10-29T12:00:00+10:00").toEpochSecond(), brisbane));
        expect("greeting 17:59", "Good afternoon",
                Times.greeting(OffsetDateTime.parse("2026-10-29T17:59:00+10:00").toEpochSecond(), brisbane));
        expect("greeting 18:00", "Good evening",
                Times.greeting(OffsetDateTime.parse("2026-10-29T18:00:00+10:00").toEpochSecond(), brisbane));

        expect("calendar codes", "Next: 6:30pm Training 2026 TERM 4",
                Times.cleanCalendar("Next: 6:30pm Training [BCE10100] 2026 TERM 4"));

        // A time typed into the calendar text by Home Assistant comes out in the house format.
        expect("typed pm", "12:00 PM Working", Times.wallClock("12:00pm Working", false));
        expect("typed pm spaced", "6:30 PM Training", Times.wallClock("6:30 pm Training", false));
        expect("typed pm in 24h", "12:00 Working", Times.wallClock("12:00pm Working", true));
        expect("typed midnight", "12:00 AM", Times.wallClock("12:00am", false));
        expect("bare time left alone", "Dinner 18:30", Times.wallClock("Dinner 18:30", false));
        expect("calendar whitespace", "Bins tonight", Times.cleanCalendar("  Bins   tonight "));
        expect("calendar empty", "", Times.cleanCalendar(null));

        long now = 1_800_000_000L;

        // The one-line calendar composer: codes out, house time, "Next:" dropped, distance appended.
        long in2h = now + 2 * 3600;
        expect("calendar line 12h", "6:30 PM Football training \u00B7 in 2 h",
                Times.calendarLine("Next: 6:30pm Football [X1] training", false, in2h, now));
        expect("calendar line 24h", "18:30 Training \u00B7 in 2 h",
                Times.calendarLine("Next: 6:30pm Training", true, in2h, now));
        expect("calendar line no time", "Bins tonight", Times.calendarLine("Bins tonight", false, 0, now));

        expect("in 2 h", "in 2 h", Times.relative(now + 2 * 3600, now));
        expect("in 25 min", "in 25 min", Times.relative(now + 25 * 60, now));
        expect("now", "now", Times.relative(now + 20, now));
        expect("ago", "12 min ago", Times.relative(now - 12 * 60, now));
        expect("no time", "", Times.relative(0, now));

        expect("kw rounding", "2.7", Times.kw("2.708"));
        expect("kw rounding up", "1.2", Times.kw("1.196"));
        expect("kw keeps one dp", "1.4", Times.kw("1.4"));
        expect("kw passthrough", "", Times.kw(""));

        expect("fresh at 15 min", "false", String.valueOf(Times.stale(900)));
        expect("stale after 15 min", "true", String.valueOf(Times.stale(901)));
        expect("age 41 min", "41m ago", Times.updatedAgo(41 * 60));
        expect("age 2 h", "2h ago", Times.updatedAgo(2 * 3600));

        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    private static void expect(String what, String want, String got) {
        checks++;
        if (!want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want \"" + want + "\", got \"" + got + "\"");
        }
    }
}
