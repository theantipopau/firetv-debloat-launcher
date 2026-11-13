package com.mihir.tvhome;

/**
 * The home screen's mood: a wash of colour over the wallpaper that follows the sun, and whether it
 * is raining. Pure rules, so they are tested without a TV.
 *
 * <p>Home Assistant sends only the NEXT sunrise and sunset. If the next sunrise comes before the
 * next sunset, the sun is down; otherwise it is up. That needs no dates and no location.
 */
final class TimeOfDay {
    private TimeOfDay() { }

    static final int NIGHT = 0x520A1228;   // cool and deep
    static final int DAWN = 0x38FF8C46;    // warm orange
    static final int SUNUP = 0x10FFD28C;   // a last hint of gold as the sun clears the horizon
    static final int CLEAR = 0x00FFB45A;   // transparent, but warm, so blending in never darkens
    static final int GOLD = 0x1AFFB45A;    // an hour before sunset
    static final int DUSK = 0x3C5A2D64;    // pink-violet at sunset

    /** ARGB wash for the moment: 0 (nothing) by day, up to {@link #NIGHT} in the dark. */
    static int tint(long now, long riseAt, long setAt) {
        if (riseAt <= 0 || setAt <= 0) return 0;   // no data, no guess
        if (riseAt < setAt) {                       // the sun is down
            long t = riseAt - now;
            if (t > 2700) return NIGHT;
            if (t > 900) return mix(NIGHT, DAWN, (2700 - t) / 1800f);
            return mix(DAWN, SUNUP, (900 - Math.max(t, 0)) / 900f);
        }
        long s = setAt - now;                       // the sun is up
        if (s > 3600) return 0;
        if (s > 1200) return mix(CLEAR, GOLD, (3600 - s) / 2400f);
        return mix(GOLD, DUSK, (1200 - Math.max(s, 0)) / 1200f);
    }

    /** Rain, pouring or a storm: the words Home Assistant's weather entity uses. */
    static boolean raining(String condition) {
        if (condition == null) return false;
        String c = condition.toLowerCase(java.util.Locale.ROOT);
        return c.contains("rain") || c.contains("pour") || c.contains("thunder") || c.contains("lightning");
    }

    /** Streaks on screen: a shower is sparse, a downpour or storm is twice as busy. */
    static int drops(String condition) {
        String c = condition == null ? "" : condition.toLowerCase(java.util.Locale.ROOT);
        return c.contains("pour") || c.contains("thunder") || c.contains("lightning") ? 90 : 45;
    }

    static int mix(int a, int b, float f) {
        f = Math.max(0f, Math.min(1f, f));
        int out = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            out |= (Math.round(ca + (cb - ca) * f) & 0xFF) << shift;
        }
        return out;
    }
}
