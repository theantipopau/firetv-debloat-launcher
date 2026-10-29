package com.mihir.tvhome;

/** Card rules that used to be invisible decisions inside the activity. */
public final class CardsTest {
    private static int checks;

    public static void main(String[] args) {
        artworkOrder();
        binsWording();
        glanceStaleness();
        System.out.println("CardsTest: " + checks + " checks passed");
    }

    private static void artworkOrder() {
        eq("a still wins", Cards.IMAGE, Cards.art("/local/still.jpg", "/local/logo.png"));
        eq("logo without a still", Cards.LOGO, Cards.art("", "/local/logo.png"));
        eq("nothing without either", Cards.NONE, Cards.art("", ""));
        eq("null is nothing", Cards.NONE, Cards.art(null, null));
    }

    private static void binsWording() {
        eq("tomorrow, short", "Bins tomorrow", Cards.binsGlance("General bins - tomorrow"));
        eq("tonight is today", "Bins today", Cards.binsGlance("General bins - out tonight"));
        eq("no day, no glance", "", Cards.binsGlance("Recycling bins"));
        eq("empty", "", Cards.binsGlance(""));
        eq("kind, general", "General", Cards.binsKind("General bins - tomorrow"));
        eq("kind, recycling", "Recycling", Cards.binsKind("Recycling bins - out tonight"));
        eq("kind, bare bins", "Bins", Cards.binsKind("Bins - tomorrow"));
        eq("day, tomorrow", "Tomorrow", Cards.binsDay("General bins - tomorrow"));
        eq("day, today", "Today", Cards.binsDay("General bins - tonight"));
        eq("day, none", "", Cards.binsDay("General bins"));
    }

    private static void glanceStaleness() {
        eq("fresh", 1f, Cards.glanceAlpha(0));
        eq("fourteen minutes", 1f, Cards.glanceAlpha(14 * 60));
        eq("sixteen minutes", 0.4f, Cards.glanceAlpha(16 * 60));
        eq("an hour", 0.4f, Cards.glanceAlpha(3600));
    }

    private static void eq(String what, int want, int got) {
        check(what + " (" + got + " == " + want + ")", want == got);
    }

    private static void eq(String what, String want, String got) {
        check(what + " (" + got + " == " + want + ")", want.equals(got));
    }

    private static void eq(String what, float want, float got) {
        check(what + " (" + got + " == " + want + ")", Math.abs(want - got) < 0.001f);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
