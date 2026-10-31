package com.mihir.tvhome;

/** Card rules that used to be invisible decisions inside the activity. */
public final class CardsTest {
    private static int checks;

    public static void main(String[] args) {
        artworkOrder();
        binsWording();
        weatherWording();
        rainWording();
        peopleWording();
        rainShort();
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
        eq("tonight is its own word", "Bins tonight", Cards.binsGlance("General bins - out tonight"));
        eq("today, short", "Bins today", Cards.binsGlance("General bins - today"));
        eq("no day, no glance", "", Cards.binsGlance("Recycling bins"));
        eq("empty", "", Cards.binsGlance(""));
        eq("kind, general", "General", Cards.binsKind("General bins - tomorrow"));
        eq("kind, recycling", "Recycling", Cards.binsKind("Recycling bins - out tonight"));
        eq("kind, bare bins", "Bins", Cards.binsKind("Bins - tomorrow"));
        eq("day, tomorrow", "Tomorrow", Cards.binsDay("General bins - tomorrow"));
        eq("day, tonight", "Tonight", Cards.binsDay("General bins - out tonight"));
        eq("day, today", "Today", Cards.binsDay("General bins - today"));
        eq("day, none", "", Cards.binsDay("General bins"));
    }

    private static void weatherWording() {
        eq("wind and humidity", "14 km/h \u00B7 62% humidity", Cards.weatherLine("14", "km/h", "62"));
        eq("wind only", "14 km/h wind", Cards.weatherLine("14", "km/h", ""));
        eq("wind without a unit", "14 wind", Cards.weatherLine("14", "", ""));
        eq("humidity only", "62% humidity", Cards.weatherLine("", "", "62"));
        eq("no weather", "", Cards.weatherLine("", "", ""));

        java.time.ZoneId utc = java.time.ZoneId.of("UTC");
        long rise = java.time.LocalDateTime.of(2026, 10, 29, 8, 0).toEpochSecond(java.time.ZoneOffset.UTC);
        long set = java.time.LocalDateTime.of(2026, 10, 29, 18, 12).toEpochSecond(java.time.ZoneOffset.UTC);
        eq("sun in 24h", "Sunrise 08:00 \u00B7 Sunset 18:12", Cards.sunLine(rise, set, true, utc));
        eq("sunset only", "Sunset 18:12", Cards.sunLine(0, set, true, utc));
        eq("sunrise only", "Sunrise 08:00", Cards.sunLine(rise, 0, true, utc));
        eq("no sun", "", Cards.sunLine(0, 0, true, utc));
    }

    private static void rainWording() {
        eq("dry, nothing else", "No rain expected", Cards.rainLine("Dry right now  -  0.0 mm today"));
        eq("dry, but when", "Next rain about 2pm", Cards.rainLine("0.0 mm today  -  next rain about 2pm"));
        eq("dry, middle dot", "Next rain about 2pm", Cards.rainLine("0.0 mm today  \u00B7  next rain about 2pm"));
        eq("dry, no tail", "No rain expected", Cards.rainLine("0.0 mm today  \u00B7  none due"));
        eq("raining", "Raining now  -  2.4 mm today", Cards.rainLine("Raining now  -  2.4 mm today"));
        eq("on the way", "Rain on the way", Cards.rainLine("Rain on the way"));
        eq("nothing", "", Cards.rainLine(""));
        eq("null", "", Cards.rainLine(null));
        eq("wet flag", true, Cards.wetRain("Raining hard"));
        eq("dry flag", false, Cards.wetRain("0.0 mm today"));
    }

    private static void peopleWording() {
        eq("three in", "3 people home", Cards.peopleLine("3", ""));
        eq("one in", "1 person home", Cards.peopleLine("1", ""));
        eq("empty house", "Nobody home", Cards.peopleLine("0", "Matt"));
        eq("nothing known", "", Cards.peopleLine("", ""));
        eq("not a number", "", Cards.peopleLine("unknown", "Matt"));
        eq("one, named", "Matt Hurley is home", Cards.peopleLine("1", "Matt Hurley"));
        eq("one, trimmed", "Matt is home", Cards.peopleLine("1", "  Matt  "));
        eq("two, named", "Matt & Caitlyn are home", Cards.peopleLine("2", "Matt & Caitlyn"));
        eq("two, named with and", "Matt & Caitlyn are home", Cards.peopleLine("2", "Matt and Caitlyn"));
        eq("name does not outvote the count", "2 people home", Cards.peopleLine("2", "Matt"));
        eq("no names, no guess", "1 person home", Cards.peopleLine("1", "  "));

        eq("a day's solar", "46.7 kWh today \u00B7 8 h 38 m left", Cards.solarLine("46.7", "8 h 38 m"));
        eq("runway, hours", "29 h", Cards.runway("29 h 15 m"));
        eq("runway, one unit", "8 h", Cards.runway("8 h"));
        eq("runway, no hours", "45 m", Cards.runway("0 h 45 m"));
        eq("runway, minutes", "45 m", Cards.runway("45 m"));
        eq("runway, days", "2 d", Cards.runway("2 d 3 h"));
        eq("runway, nothing", "", Cards.runway(""));
        eq("runway, unknown", "", Cards.runway("unknown"));
        eq("runway, null", "", Cards.runway(null));
        eq("today only", "46.7 kWh today", Cards.solarLine("46.7", ""));
        eq("battery only", "Battery 8 h 38 m left", Cards.solarLine("", "8 h 38 m"));
        eq("no energy", "", Cards.solarLine("", ""));
    }

    private static void rainShort() {
        eq("headline and amount", "Rain on the way · 0.8 mm in 3 h",
                Cards.rainShort("Rain on the way  -  ~0.8 mm in 3 h  ·  13% chance"));
        eq("a dry day keeps its next rain", "Next rain about 2pm",
                Cards.rainShort("0.0 mm today - next rain about 2pm"));
        eq("nothing", "", Cards.rainShort(""));
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

    private static void eq(String what, boolean want, boolean got) {
        check(what + " (" + got + " == " + want + ")", want == got);
    }

    private static void eq(String what, float want, float got) {
        check(what + " (" + got + " == " + want + ")", Math.abs(want - got) < 0.001f);
    }

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
