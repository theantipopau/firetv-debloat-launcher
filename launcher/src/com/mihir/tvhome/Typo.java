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
    static final float CLOCK = 96f;
    static final float TEMPERATURE = 72f;
    static final float HEADING = 28f;
    static final float BODY = 22f;
    static final float CAPTION = 18f;

    /** Small labels used to breathe at 0.22-0.25em; Inter's larger x-height carries 0.11. */
    static final float LABEL_TRACKING = 0.11f;

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

    /** The floor: no role in this UI is smaller than a caption. */
    static float atLeastCaption(float sp) {
        return Math.max(CAPTION, sp);
    }

    /** Tabular figures, for the clock, the temperature and anything measured in kW or %. */
    static void numbers(TextView t) {
        t.setFontFeatureSettings("tnum");
    }
}
