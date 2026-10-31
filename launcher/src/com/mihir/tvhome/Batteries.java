package com.mihir.tvhome;

/**
 * What a battery line means, kept free of Android so tests/ can hold it still.
 *
 * Home Assistant reports the FoxESS battery as two separate magnitudes - a charge power and a
 * discharge power, both positive - so a single signed number has no inherent direction. The
 * household preference is a setting, and {@link #state} takes it as an argument rather than
 * guessing, because guessing wrong draws a charging battery as if it were draining the house.
 */
final class Batteries {
    static final int IDLE = 0, CHARGING = 1, DISCHARGING = 2, LOW = 3;

    /** Below this the inverter is idling: 50 W is under a percent of the pack either way. */
    static final double DEADBAND_W = 50;

    /** Positive means charging until the household says otherwise: HA's own template tests
     *  {@code charge > 0.05} for "charging", and both of its power entities are magnitudes. */
    static final boolean DEFAULT_POSITIVE_IS_CHARGING = true;

    static final int GREY = 0xFF8E8E93;

    private Batteries() { }

    /**
     * @param soc                   state of charge, percent
     * @param watts                 signed power, watts; positive direction set by the flag
     * @param positiveMeansCharging how to read the sign
     */
    static int state(double soc, double watts, boolean positiveMeansCharging) {
        double charge = positiveMeansCharging ? watts : -watts;
        int s;
        if (charge > DEADBAND_W) s = CHARGING;
        else if (charge < -DEADBAND_W) s = DISCHARGING;
        else s = IDLE;
        // "Low (under 20% and not charging)": a battery on the way up is never alarming.
        if (soc < 20 && s != CHARGING) s = LOW;
        return s;
    }

    /** Green charging, orange discharging, red low, white 70% idle. */
    static int color(int state) {
        switch (state) {
            case CHARGING: return 0xFF34C759;
            case DISCHARGING: return 0xFFFF9F0A;
            case LOW: return 0xFFFF453A;
            default: return 0xB3FFFFFF;
        }
    }

    /** Stale data is greyed whatever it is doing, and dimmed to 40%. */
    static int color(int state, boolean stale) {
        return stale ? GREY : color(state);
    }

    static float alpha(boolean stale) {
        return stale ? 0.4f : 1f;
    }

    /** The mark after the percentage: up while charging, down while supplying the house. */
    static String glyph(int state) {
        switch (state) {
            case CHARGING: return "\u2191";
            case DISCHARGING: return "\u2193";
            default: return "";
        }
    }

    /** "Battery 84%": the name and the number, which is all a line has room for. */
    static String label(String name, double soc) {
        String n = name == null ? "" : name.trim();
        String pct = Math.round(soc) + "%";
        return n.isEmpty() ? pct : n + " " + pct;
    }

    /** The line as the status block prints it, e.g. "Battery 84% ↑". */
    static String line(String name, double soc, int state) {
        String g = glyph(state);
        return g.isEmpty() ? label(name, soc) : label(name, soc) + " " + g;
    }
}
