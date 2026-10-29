package com.mihir.tvhome;

import java.util.List;

/**
 * Plain JVM tests for the two live feeds. The regression these guard against is real: v1.3 merged
 * Continue Watching into the hero rotation, where four live programmes sat in front of it. Nothing
 * looks broken in the JSON, so only a test of the *selection* catches it.
 *
 * Run with tests/run.sh (no JUnit, no Gradle).
 */
public final class FeedTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        // tvhome_new.json: live programmes have kind=live and until; new titles have kind=new.
        // The escaped quote and \u00b7 are here because the real feed is written with ensure_ascii=False
        // and a show called "Bob's Burgers" would otherwise be a parser bug waiting to happen.
        String live = "{\"sig\":\"x\",\"items\":["
                + "{\"kind\":\"live\",\"app\":\"TiviMate\",\"pkg\":\"ar.tvplayer.tv\",\"title\":\"Test - South Africa v Australia\","
                + "\"channel\":\"7AFL\",\"caption\":\"7AFL \u00B7 until 21:00\",\"until\":1793239200,\"progress\":0,"
                + "\"badge\":\"http://ha/local/tvhome/live0_badge.png\"},"
                + "{\"kind\":\"live\",\"title\":\"NBA Basketball\",\"channel\":\"ESPN\",\"until\":1793237700},"
                + "{\"kind\":\"new\",\"app\":\"Plex\",\"title\":\"Verity\",\"sub\":\"2026 \\u00b7 2h 00m\",\"progress\":0,"
                + "\"image\":\"http://ha/local/tvhome/new1_bg.jpg\"},"
                + "{\"kind\":\"new\",\"app\":\"Plex\",\"title\":\"Bob's Burgers\",\"sub\":\"S3 \\u00b7 E4 \\u00b7 New episode\"},"
                + "{\"kind\":\"new\",\"app\":\"Plex\",\"title\":\"Bob's Burgers\",\"sub\":\"S3 \\u00b7 E4 \\u00b7 New episode\"}]}";

        List<Feed.Item> parsed = Feed.parse(live);
        expect("parsed count", "5", String.valueOf(parsed.size()));
        expect("live flag", "true", String.valueOf(parsed.get(0).live()));
        expect("live until", "1793239200", String.valueOf(parsed.get(0).until));
        // A live item says "caption", not "sub": the card's third line reads either.
        expect("live caption as sub", "7AFL \u00B7 until 21:00", parsed.get(0).sub);
        // The channel mark the caption shows instead of the channel's name (v1.4); absent means text.
        expect("live channel badge", "http://ha/local/tvhome/live0_badge.png", parsed.get(0).badge);
        expect("no badge, no logo", "", parsed.get(1).badge);
        expect("escaped apostrophe", "Bob's Burgers", parsed.get(3).title);
        expect("unicode middle dot", "2026 \u00b7 2h 00m", parsed.get(2).sub);

        List<Feed.Item> hero = Feed.hero(parsed);
        expect("hero count", "4", String.valueOf(hero.size()));   // 2 live + 2 distinct new, cap 5
        expect("hero starts live", "true", String.valueOf(hero.get(0).live()));
        expect("hero second is live", "true", String.valueOf(hero.get(1).live()));
        expect("hero has no resume", "false", String.valueOf(hero.get(2).live()));
        int bobs = 0;
        for (Feed.Item it : hero) if ("Bob's Burgers".equals(it.title)) bobs++;
        expect("hero dedupes new", "1", String.valueOf(bobs));

        // The hero never grows past five, however many live programmes the guide finds.
        StringBuilder many = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 9; i++) {
            if (i > 0) many.append(',');
            many.append("{\"kind\":\"live\",\"title\":\"Match ").append(i).append("\",\"until\":1793239200}");
        }
        many.append("]}");
        expect("hero cap", "5", String.valueOf(Feed.hero(Feed.parse(many.toString())).size()));

        // tvhome_keep.json: resume cards, no kind. This is the row that went missing.
        String keep = "{\"items\":["
                + "{\"app\":\"Plex\",\"pkg\":\"com.plexapp.android\",\"title\":\"Chuck\",\"sub\":\"S1 \\u00b7 E1 \\u00b7 13 min left\","
                + "\"progress\":0.699,\"image\":\"http://ha/local/tvhome/plex0_bg.jpg\","
                + "\"uri\":\"plex://server://abc/com.plexapp.plugins.library/library/metadata/42\"},"
                + "{\"app\":\"Plex\",\"pkg\":\"com.plexapp.android\",\"title\":\"Animal Farm\","
                + "\"sub\":\"2026 \\u00b7 26 min left\",\"progress\":0.721},"
                + "{\"app\":\"TiviMate\",\"pkg\":\"ar.tvplayer.tv\",\"title\":\"Live TV\",\"sub\":\"Resume last channel\"}]}";

        List<Feed.Item> cw = Feed.continueWatching(Feed.parse(keep));
        expect("cw count", "3", String.valueOf(cw.size()));
        expect("cw title", "Chuck", cw.get(0).title);
        expect("cw sub", "S1 \u00b7 E1 \u00b7 13 min left", cw.get(0).sub);
        expect("cw progress", "0.699", String.valueOf(cw.get(0).progress));
        expect("cw uri", "plex://server://abc/com.plexapp.plugins.library/library/metadata/42", cw.get(0).uri);
        expect("cw image", "http://ha/local/tvhome/plex0_bg.jpg", cw.get(0).image);
        expect("cw no-image card", "", cw.get(2).image);

        // A live programme or a new-on-Plex card must never be treated as a resume card, and the
        // two feeds can be selected independently of each other.
        expect("cw drops live and new", "0", String.valueOf(Feed.continueWatching(parsed).size()));
        List<Feed.Item> mixed = Feed.parse(keep);
        mixed.addAll(parsed);
        expect("cw from a mixed list", "3", String.valueOf(Feed.continueWatching(mixed).size()));

        // Broken input is an empty list, never half of one.
        expect("null body", "0", String.valueOf(Feed.parse(null).size()));
        expect("not json", "0", String.valueOf(Feed.parse("<html>503</html>").size()));
        expect("truncated", "0", String.valueOf(Feed.parse("{\"items\":[{\"title\":\"Chuck\"").size()));
        expect("no items", "0", String.valueOf(Feed.parse("{\"sig\":\"x\"}").size()));
        expect("tabs and newlines", "1",
                String.valueOf(Feed.parse("{\n\t\"items\" : [ {\"title\" : \"Chuck\"} ]\n}").size()));

        System.out.println(checks + " checks, " + failures + " failed");
        if (failures > 0) System.exit(1);
    }

    private static void expect(String what, String want, String got) {
        checks++;
        if (!want.equals(got)) {
            failures++;
            System.out.println("FAIL " + what + ": want \"" + want + "\", got \"" + got + "\"");
        }
    }
}
