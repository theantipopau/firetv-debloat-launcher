package com.mihir.tvhome;

/** The hero title's shrink rule: fits one line when it can, never drops below the floor. */
public final class TitleFitTest {
    private static int checks;

    /** A stand-in for Paint: roughly half an em per character, so the numbers are predictable. */
    private static final TitleFit.Measure HALF_EM = (text, sp) -> text.length() * sp * 0.5f;

    public static void main(String[] args) {
        fitsAtTheCeiling();
        shrinksToFit();
        floorsAtTheMinimum();
        ignoresEmptyText();
        System.out.println("TitleFitTest: " + checks + " checks passed");
    }

    private static void fitsAtTheCeiling() {
        // "LPGA" at 48sp measures 96px; a 400px line keeps the ceiling.
        eq("short title keeps 48", 48f, TitleFit.size("LPGA", 48f, 36f, 400f, HALF_EM));
    }

    private static void shrinksToFit() {
        // 33 characters at 48sp = 792px; the 650px line needs 38sp (33 * 0.5 * 38 = 627).
        String title = "Asia Pacific Amateur Championship";
        float size = TitleFit.size(title, 48f, 36f, 650f, HALF_EM);
        eq("long title shrinks", 38f, size);
        check("chosen size fits", size * title.length() * 0.5f <= 650f);
    }

    private static void floorsAtTheMinimum() {
        // 60 characters never fit one line: the caller gets the floor and wraps to a second line.
        eq("unfittable title floors", 36f, TitleFit.size("x".repeat(60), 48f, 36f, 650f, HALF_EM));
    }

    private static void ignoresEmptyText() {
        eq("empty title keeps the ceiling", 48f, TitleFit.size("", 48f, 36f, 650f, HALF_EM));
    }

    private static void eq(String what, float want, float got) {
        check(what + " (" + got + " == " + want + ")", Math.abs(want - got) < 0.01f);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
