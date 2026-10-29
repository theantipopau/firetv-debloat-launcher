package com.mihir.tvhome;

import android.graphics.drawable.GradientDrawable;

/**
 * The home screen's geometry and light, in one place.
 *
 * The launcher and the Home-key cover are two windows onto the same screen: the cover is what the
 * viewer sees for the first few hundred milliseconds of every Home press, so a margin that lives
 * twice drifts, and the screen visibly jumps when the real launcher arrives. Every number below is
 * in dp; callers convert with their own density. Text sizes are in sp and live in {@link Typo}.
 */
final class HomeTheme {
    private HomeTheme() { }

    // TV-safe area: nothing important sits closer than this to an edge.
    static final int SAFE = 48;

    // ---- top-left block: the greeting, the clock, the date, then three or four plain lines
    static final int CLOCK_TOP = 26;
    /** From the greeting's baseline to the clock's cap. */
    static final int GREET_GAP = 6;
    /**
     * The date hangs under the clock inside the line box's own descent, so it costs no height and
     * the card below does not move.
     */
    static final int DATE_TOP = -12;
    /** Gap from the clock's baseline to the first line. */
    static final int CLOCK_GAP = 10;
    static final int LINE_GAP = 6;
    static final int LINE_ICON = 14;
    static final int LINE_ICON_GAP = 8;
    /** The whole block is one focus target; its highlight is a whisper, not a panel. */
    static final int BLOCK_RADIUS = 16;
    static final int BLOCK_PAD_H = 12;
    static final int BLOCK_PAD_V = 6;

    // ---- top-right actions
    static final int ACTION_TOP = 46;
    static final int ACTION_GAP = 28;

    // ---- Now Showing card
    /**
     * One gutter for the whole hero, and it is the dock's own: 14dp between the two cards, 14dp from
     * the row down to the dock's glass, the same 14dp the dock keeps between its tiles. Focus never
     * changes it - the card highlights in place instead of growing into the gap, so the row's spacing
     * is identical whether a card is focused or not.
     */
    static final int GUTTER = 14;
    /** Two cards, side by side: (960 - 2*48 - 14) / 2. */
    static final int NOW_W = 425;
    static final int NOW_H = 96;
    /** The gap between the two cards, centred on the screen. */
    static final int NOW_GAP_X = GUTTER;
    /** Absolute: the row of cards starts here, 14dp above the dock's glass (540 - 56 - 99 - 14 - 96). */
    static final int NOW_TOP = 275;
    static final int NOW_RADIUS = 16;
    /** From the last status line to the card. */
    static final int NOW_GAP = 12;
    static final int NOW_PAD = 12;
    static final int NOW_ART_W = 120;      // 16:9 inside the 96dp card
    static final int NOW_ART_H = 68;
    static final int NOW_ART_RADIUS = 10;
    static final int NOW_TEXT_GAP = 14;

    // ---- dock: one glass pill of banner tiles, name under each
    /** 8dp above the 48dp safe margin: the pill sits lower now that the greeting needs the room. */
    static final int DOCK_BOTTOM = SAFE + 8;
    /** Eight 16:9 tiles and their gaps fit inside 960dp at this size. */
    static final int DOCK_W = 100;
    static final int DOCK_H = 56;
    static final int DOCK_GAP = 14;
    static final int DOCK_PAD = 12;
    static final int DOCK_NAME = 12;      // sp
    static final int DOCK_NAME_GAP = 5;
    static final int DOCK_ICON_RADIUS = 12;
    /** Tile + 6dp of glow room each side + the name line: one slot's height. */
    static final int DOCK_SLOT_H = DOCK_H + 12 + DOCK_NAME_GAP + 18;
    /** The glass around the slots: its own vertical padding is 8 less because the slots carry it. */
    static final int DOCK_PILL_H = DOCK_SLOT_H + (DOCK_PAD - 8) * 2;
    /**
     * The glass is a rounded bar, not a capsule: a 49dp radius cuts across the corner of a lifted
     * tile at either end, so the glass edge appears to clip it. 26dp keeps the curve clear.
     */
    static final int DOCK_PILL_RADIUS = 26;
    /**
     * Uneven vertical padding inside the glass: the tiles sit 4dp lower than centred, which is what
     * keeps a lifted end tile clear of the glass's top curve instead of hanging over it.
     */
    static final int DOCK_PAD_TOP = 6;
    static final int DOCK_PAD_BOTTOM = 2;
    /** Focus is a lift, not a new size: 1.06 with a soft white glow behind the tile. */
    static final float DOCK_FOCUS_SCALE = 1.06f;
    static final int DOCK_FOCUS_LIFT = 2;

    /**
     * The cover's loading line sits on the floor below the pill, not at the pill's own height: at the
     * safe margin the wheel landed on the glass's right end and read as part of the dock.
     */
    static final int LOADING_BOTTOM = 18;

    /** The 16:9 grid cards below the fold keep their old radius. */
    static final int TILE_RADIUS = 14;

    // ---- Continue Watching: hidden at rest, revealed by Down from the dock.
    static final int CW_W = 240;
    static final int CW_H = 135;           // 16:9
    static final int CW_GAP = 16;
    static final int CW_RADIUS = 12;
    static final int CW_HEAD_GAP = 12;
    /** The revealed row sits this far above the pill. */
    static final int CW_TO_DOCK = 22;
    static final int PROGRESS_H = 4;
    static final int PROGRESS_INSET = 12;
    static final float CW_FOCUS_SCALE = 1.06f;
    /** The tile's floor gradient covers the lower 60%: title, metadata and bar all sit on it. */
    static final float CW_SHADE = 0.60f;
    static final int CW_REVEAL_MS = 220;

    // ---- Home Assistant sheet
    static final int HA_W = 336;           // 35% of a 960dp screen
    static final int HA_MARGIN = 24;
    static final int HA_RADIUS = 24;
    /** 98% #0E0E10 over the sampled blur: nothing behind the sheet reads through it. */
    static final int HA_TINT = 0xFA0E0E10;

    // ---- glass
    /** The dock pill and the Now Showing card: black 35% plus a 1dp white 12% hairline. */
    static final int GLASS = 0x59000000;
    static final int GLASS_EDGE = 0x1FFFFFFF;

    // ---- text and alerts
    static final int TEXT_90 = 0xE6FFFFFF;
    static final int TEXT_70 = 0xB3FFFFFF;
    static final int TEXT_60 = 0x99FFFFFF;
    /** The one accent: something needs attention (bins tonight, a warning). */
    static final int ACCENT = 0xFFFFC857;
    static final int RIM = 0x33FFFFFF;

    // ---- battery states
    static final int BAT_GREEN = 0xFF34C759;
    static final int BAT_ORANGE = 0xFFFF9F0A;
    static final int BAT_RED = 0xFFFF453A;
    static final int BAT_IDLE = TEXT_70;

    // ---- backdrop: two gradients, nothing else
    static GradientDrawable leftWash() {
        return horizontal(new int[]{0x73000000, 0x1F000000, 0x00000000});
    }

    static GradientDrawable bottomWash() {
        return vertical(new int[]{0x00000000, 0x33000000, 0x80000000});
    }

    /** Deeper floor behind a revealed Continue Watching row; drawn in, never at rest. */
    static GradientDrawable revealWash() {
        return vertical(new int[]{0x00000000, 0x99000000, 0xE6000000});
    }

    static GradientDrawable glassPill(int radiusDp, float density) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(radiusDp * density);
        g.setColor(GLASS);
        g.setStroke(Math.max(1, Math.round(density)), GLASS_EDGE);
        return g;
    }

    static GradientDrawable vertical(int[] colors) {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
    }

    static GradientDrawable horizontal(int[] colors) {
        return new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors);
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
