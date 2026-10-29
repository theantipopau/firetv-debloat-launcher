package com.mihir.tvhome;

/** Pure rules for the hero title: shrink before wrapping, and never below the scale's floor. */
final class TitleFit {
    interface Measure {
        /** Width of {@code text} at {@code sp}, in px. */
        float width(String text, float sp);
    }

    private TitleFit() { }

    /**
     * The largest size between the ceiling and the floor that fits one line; the floor when even
     * that overflows (the caller then allows a second line).
     */
    static float size(String text, float maxSp, float minSp, float maxWidth, Measure m) {
        if (text == null || text.isEmpty() || maxWidth <= 0) return maxSp;
        for (float sp = maxSp; sp >= minSp; sp -= 2f) {
            if (m.width(text, sp) <= maxWidth) return sp;
        }
        return minSp;
    }
}
