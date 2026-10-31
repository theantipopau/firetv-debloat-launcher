package com.mihir.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.widget.TextView;

/**
 * Every type decision in one place: the four bundled Inter weights, the scale, and the two rules
 * the home screen follows everywhere - nothing below 18sp, and figures are tabular so a clock or
 * a wattage does not jitter as its digits change.
 *
 * Inter ships as static TTFs rather than the variable font because Fire OS 7's text stack cannot
 * instance variable fonts. Licence: LICENSES/Inter-OFL.txt.
 */
final class Typo {
    // The home screen's scale (v1.3.1): nothing above the hero title, nothing below a caption.
    static final float HERO = 48f;        // hero title; the ceiling for the whole screen
    static final float CLOCK_BAR = 28f;   // the top-bar clock
    static final float SECTION = 22f;     // "Continue Watching", "Apps"
    static final float BODY = 20f;        // greeting, glance values, hero meta
    static final float CAPTION = 18f;     // labels, chips, tile metadata

    // The full-screen clock saver predates this scale and is its own surface.
    static final float CLOCK = 96f;
    static final float TEMPERATURE = 72f;
    static final float HEADING = 28f;

    private static Typeface light, regular, medium, semibold;

    private Typo() { }

    static Typeface light(Context c) {
        if (light == null) light = c.getResources().getFont(R.font.inter_light);
        return light;
    }

    static Typeface regular(Context c) {
        if (regular == null) regular = c.getResources().getFont(R.font.inter_regular);
        return regular;
    }

    static Typeface medium(Context c) {
        if (medium == null) medium = c.getResources().getFont(R.font.inter_medium);
        return medium;
    }

    static Typeface semibold(Context c) {
        if (semibold == null) semibold = c.getResources().getFont(R.font.inter_semibold);
        return semibold;
    }

    /**
     * The old views asked for a system family by name ("sans-serif-light", "sans-serif-medium").
     * Mapping those names keeps every call site working while the whole screen moves to Inter.
     */
    static Typeface family(Context c, String family) {
        if (family == null) return regular(c);
        if (family.contains("thin") || family.contains("light")) return light(c);
        if (family.contains("medium") || family.contains("condensed")) return medium(c);
        if (family.contains("bold") || family.contains("black")) return semibold(c);
        return regular(c);
    }

    /** "Good morning, Hurleys": the greeting and the household, the way a person would write it. */
    static String hello(String greeting, String family) {
        return family == null || family.isEmpty() ? greeting : greeting + ", " + family;
    }

    /** The floor: no role in this UI is smaller than a caption. */
    static float atLeastCaption(float sp) {
        return Math.max(CAPTION, sp);
    }

    /** Tabular figures, for the clock, the temperature and anything measured in kW or %. */
    static void numbers(TextView t) {
        t.setFontFeatureSettings("tnum");
    }
}
