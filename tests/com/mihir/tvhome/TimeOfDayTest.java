package com.mihir.tvhome;

/** The sun-following wash and the rain rules. */
public final class TimeOfDayTest {
    private static int checks;

    public static void main(String[] args) {
        long now = 1_000_000;
        eq("no data, no wash", 0, TimeOfDay.tint(now, 0, 0));
        eq("deep night", TimeOfDay.NIGHT, TimeOfDay.tint(now, now + 6 * 3600, now + 18 * 3600));
        eq("an hour to sunrise is still night", TimeOfDay.NIGHT, TimeOfDay.tint(now, now + 2700, now + 40000));
        eq("15 minutes to sunrise is dawn", TimeOfDay.DAWN, TimeOfDay.tint(now, now + 900, now + 40000));
        eq("sun just up is nearly clear", TimeOfDay.SUNUP, TimeOfDay.tint(now, now, now + 40000));
        eq("midday has no wash", 0, TimeOfDay.tint(now, now + 14 * 3600, now + 6 * 3600));
        eq("golden hour begins clear", TimeOfDay.CLEAR, TimeOfDay.tint(now, now + 14 * 3600, now + 3600));
        eq("20 minutes to sunset is golden", TimeOfDay.GOLD, TimeOfDay.tint(now, now + 14 * 3600, now + 1200));
        eq("at sunset it is dusk", TimeOfDay.DUSK, TimeOfDay.tint(now, now + 14 * 3600, now));
        eq("halfway between night and dawn", 0x45 << 24 | 0x85 << 16 | 0x4F << 8 | 0x37,
                TimeOfDay.tint(now, now + 1800, now + 40000) & 0xFFFFFFFF, 0);
        yes("rainy", TimeOfDay.raining("rainy"));
        yes("lightning rainy", TimeOfDay.raining("lightning rainy"));
        yes("pouring", TimeOfDay.raining("Pouring"));
        yes("thunderstorms", TimeOfDay.raining("thunderstorms"));
        yes("partly cloudy is dry", !TimeOfDay.raining("partly cloudy"));
        yes("empty is dry", !TimeOfDay.raining(""));
        yes("null is dry", !TimeOfDay.raining(null));
        yes("a storm flashes", TimeOfDay.stormy("lightning rainy"));
        yes("thunderstorms flash", TimeOfDay.stormy("thunderstorms"));
        yes("plain rain does not", !TimeOfDay.stormy("rainy"));
        eq("a shower", 45, TimeOfDay.drops("rainy"));
        eq("a downpour", 90, TimeOfDay.drops("pouring"));
        eq("a storm", 90, TimeOfDay.drops("thunderstorms"));
        System.out.println("TimeOfDayTest: " + checks + " checks passed");
    }

    private static void yes(String what, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static void eq(String what, int want, int got) {
        checks++;
        if (want != got) throw new AssertionError(what + ": want " + Integer.toHexString(want) + " got " + Integer.toHexString(got));
    }

    /** Within a few steps of one channel: rounding in the middle of a blend is not the point. */
    private static void eq(String what, int want, int got, int slack) {
        checks++;
        for (int shift = 24; shift >= 0; shift -= 8) {
            if (Math.abs(((want >>> shift) & 0xFF) - ((got >>> shift) & 0xFF)) > 2) {
                throw new AssertionError(what + ": want " + Integer.toHexString(want) + " got " + Integer.toHexString(got));
            }
        }
    }
}
