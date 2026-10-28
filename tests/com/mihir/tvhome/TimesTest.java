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

        // The clock's own digits never carry AM/PM - that is drawn beside it.
        expect("clock digits 12h", "4:00", Times.clockDigits(nz, false, brisbane));
        expect("clock digits 24h", "04:00", Times.clockDigits(nz, true, brisbane));

        expect("calendar codes", "Next: 6:30pm Training 2026 TERM 4",
                Times.cleanCalendar("Next: 6:30pm Training [BCE10100] 2026 TERM 4"));
        expect("calendar whitespace", "Bins tonight", Times.cleanCalendar("  Bins   tonight "));
        expect("calendar empty", "", Times.cleanCalendar(null));

        long now = 1_800_000_000L;
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
