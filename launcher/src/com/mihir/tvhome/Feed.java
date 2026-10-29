package com.mihir.tvhome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The two Home Assistant feeds - tvhome_new.json (live now, then new on Plex) and
 * tvhome_keep.json (what you are part-way through) - parsed and split here.
 *
 * Deliberately free of Android and org.json so tests/ can run it on a plain JVM: the rule that
 * "Continue Watching never goes in the hero" is exactly the kind of thing that regresses silently.
 * The parser is small and strict (objects, arrays, strings, numbers, booleans, null); anything it
 * cannot read yields an empty list rather than a half-filled one.
 */
final class Feed {
    /** Live programmes first, then new titles: five frames is a 45 s loop, not a slideshow. */
    static final int HERO_MAX = 5;
    /** Enough resume cards to scroll through without turning the row into a library. */
    static final int KEEP_MAX = 12;

    static final class Item {
        String kind = "";      // "live", "new", or "" for a resume card
        String app = "", title = "", sub = "", channel = "";
        String image = "", logo = "", uri = "", pkg = "";
        long until;            // epoch seconds, live only
        double progress;       // 0..1
        boolean artfit;        // the image is a logo, show it contained

        boolean live() { return "live".equals(kind); }
        String key() { return title.toLowerCase(Locale.US) + "|" + sub; }
    }

    private Feed() { }

    /** Every card in the feed, in file order; empty when the body is missing or malformed. */
    static List<Item> parse(String body) {
        List<Item> out = new ArrayList<>();
        if (body == null) return out;
        Object root;
        try {
            root = new Reader(body).value();
        } catch (RuntimeException e) {
            return out;
        }
        if (!(root instanceof Map)) return out;
        Object arr = ((Map<?, ?>) root).get("items");
        if (!(arr instanceof List)) return out;
        for (Object o : (List<?>) arr) {
            if (o instanceof Map) out.add(item((Map<?, ?>) o));
        }
        return out;
    }

    /**
     * The hero rotation: what is live now, then what is new. Resume cards are NEVER included -
     * they belong in their own row, where they can be seen without waiting a minute for a turn.
     */
    static List<Item> hero(List<Item> newFeed) {
        List<Item> out = new ArrayList<>();
        for (Item it : newFeed) {
            if (it.live()) {
                out.add(it);
                if (out.size() >= HERO_MAX) return out;
            }
        }
        Map<String, Boolean> seen = new HashMap<>();
        for (Item it : newFeed) {
            if (it.live()) continue;
            if (seen.put(it.key(), Boolean.TRUE) != null) continue;
            out.add(it);
            if (out.size() >= HERO_MAX) break;
        }
        return out;
    }

    /** The Continue Watching row, in feed order (the script already puts part-watched first). */
    static List<Item> continueWatching(List<Item> keep) {
        List<Item> out = new ArrayList<>();
        for (Item it : keep) {
            if (it.live() || "new".equals(it.kind)) continue;
            out.add(it);
            if (out.size() >= KEEP_MAX) break;
        }
        return out;
    }

    private static Item item(Map<?, ?> m) {
        Item it = new Item();
        it.kind = str(m, "kind");
        it.app = str(m, "app");
        it.title = str(m, "title");
        it.sub = str(m, "sub");
        it.channel = str(m, "channel");
        it.image = str(m, "image");
        it.logo = str(m, "logo");
        it.uri = str(m, "uri");
        it.pkg = str(m, "pkg");
        it.until = (long) num(m, "until");
        it.progress = num(m, "progress");
        it.artfit = num(m, "artfit") > 0;
        return it;
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static double num(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    /** A recursive-descent reader for the subset above. Throws on anything it does not understand. */
    private static final class Reader {
        private final String s;
        private int i;

        Reader(String s) { this.s = s; }

        Object value() {
            ws();
            if (i >= s.length()) throw fail();
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (c == 't') { literal("true"); return Boolean.TRUE; }
            if (c == 'f') { literal("false"); return Boolean.FALSE; }
            if (c == 'n') { literal("null"); return null; }
            return number();
        }

        private Map<String, Object> object() {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            i++;   // {
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw fail();
                String k = string();
                ws();
                if (peek() != ':') throw fail();
                i++;
                m.put(k, value());
                ws();
                char c = peek();
                i++;
                if (c == '}') return m;
                if (c != ',') throw fail();
            }
        }

        private List<Object> array() {
            List<Object> a = new ArrayList<>();
            i++;   // [
            ws();
            if (peek() == ']') { i++; return a; }
            while (true) {
                a.add(value());
                ws();
                char c = peek();
                i++;
                if (c == ']') return a;
                if (c != ',') throw fail();
            }
        }

        private String string() {
            StringBuilder b = new StringBuilder();
            i++;   // opening quote
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                if (i >= s.length()) throw fail();
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'n': b.append('\n'); break;
                    case 'r': b.append('\r'); break;
                    case 't': b.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw fail();
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw fail();
                }
            }
            throw fail();
        }

        private Object number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw fail();
            String t = s.substring(st, i);
            try {
                if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) return Long.valueOf(t);
                return Double.valueOf(t);
            } catch (NumberFormatException e) {
                throw fail();
            }
        }

        private void literal(String word) {
            if (!s.startsWith(word, i)) throw fail();
            i += word.length();
        }

        private void ws() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return;
                i++;
            }
        }

        private char peek() {
            if (i >= s.length()) throw fail();
            return s.charAt(i);
        }

        private RuntimeException fail() {
            return new IllegalArgumentException("bad json at " + i);
        }
    }
}
