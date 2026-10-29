package com.mihir.tvhome;

import android.graphics.drawable.GradientDrawable;

/**
 * The home screen's geometry and light, in one place.
 *
 * The launcher and the Home-key cover are two windows onto the same screen: the cover is what the
 * viewer sees for the first few hundred milliseconds of every Home press, so a margin that lives
 * twice drifts, and the screen visibly jumps when the real launcher arrives. Every number below is
 * in dp; callers convert with their own density.
 */
final class HomeTheme {
    private HomeTheme() { }

    // TV-safe area: nothing important sits closer than this to an edge.
    static final int SAFE = 48;

    // Top bar.
    static final int TOP_BAR_TOP = 16;
    static final int TOP_BAR_H = 64;
    /** The shade behind the bar: black 70% at the edge, gone by 200dp. */
    static final int TOP_SCRIM_H = 200;

    // Dock.
    static final int DOCK_PAD_H = 48;
    static final int DOCK_PAD_V = 10;
    static final int DOCK_GAP = 10;
    static final int TILE_RADIUS = 14;
    /** The tile the cursor starts on: home and the cover must draw it at the same size. */
    static final float DOCK_FOCUS_SCALE = 1.08f;
    static final int DOCK_FOCUS_LIFT = 4;

    // Continue Watching.
    static final int CW_HEAD_GAP = 10;
    static final int CW_GAP = 12;
    static final int CW_W = 184;
    static final int CW_H = 104;
    static final int CW_RADIUS = 12;
    static final int CW_BOTTOM = 48;
    static final int DOCK_TO_CW = 28;
    static final int PROGRESS_H = 4;
    static final int PROGRESS_INSET = 12;
    static final float CW_FOCUS_SCALE = 1.06f;
    /** The tile's floor gradient covers the lower 60%: title, metadata and bar all sit on it. */
    static final float CW_SHADE = 0.60f;

    // Hero copy sits this far above the dock; the page dots share the meta line's baseline.
    static final int META_TO_DOCK = 32;

    // Home Assistant sheet.
    static final int HA_W = 336;           // 35% of a 960dp screen
    static final int HA_MARGIN = 24;
    static final int HA_RADIUS = 24;
    /** 98% #0E0E10 over the sampled blur: nothing behind the sheet reads through it. */
    static final int HA_TINT = 0xFA0E0E10;

    // Text and alerts.
    static final int TEXT_70 = 0xB3FFFFFF;
    static final int TEXT_60 = 0x99FFFFFF;
    static final int ACCENT = 0xFFFFC857;  // the one accent: something needs attention
    static final int RIM = 0x33FFFFFF;

    /** The launcher's five-stop wash over the wallpaper, reused by the cover. */
    static GradientDrawable wallpaperScrim() {
        return vertical(new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000});
    }

    /** Black 70% at the top edge, gone by 200dp: keeps the bar readable on a bright still, and
     * covers the title a broadcast poster has printed inside its own top band. */
    static GradientDrawable topScrim() {
        return vertical(new int[]{0xB3000000, 0x4D000000, 0x00000000});
    }

    /** Black 70% at the left, gone by the middle: the title sits on its own shadow. */
    static GradientDrawable leftScrim() {
        return horizontal(new int[]{0xC0000000, 0x5E000000, 0x00000000});
    }

    static GradientDrawable bottomScrim() {
        return vertical(new int[]{0x00000000, 0x1A000000, 0x8C000000, 0xF0000000});
    }

    /** A soft floor for the dock and the row under it, so they never sit on raw artwork. */
    static GradientDrawable dockFade() {
        return vertical(new int[]{0x00000000, 0x59000000, 0xA6000000});
    }

    static GradientDrawable vertical(int[] colors) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
    }

    static GradientDrawable horizontal(int[] colors) {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
    }

    /** Time-of-day wash over the wallpaper: warm after six, cool before ten, clear otherwise. */
    static int timeTint(int hour) {
        return hour >= 18 || hour < 5 ? 0x26FF7A2E : hour < 10 ? 0x1A7FB8FF : 0x00000000;
    }

    static int withAlpha(int c, float a) {
        return android.graphics.Color.argb(Math.round(a * 255),
                android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c));
    }

    /** Toward black; {@code f} is how much of the colour survives. */
    static int darken(int c, float f) {
        int r = (int) (android.graphics.Color.red(c) * f);
        int g = (int) (android.graphics.Color.green(c) * f);
        int b = (int) (android.graphics.Color.blue(c) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
