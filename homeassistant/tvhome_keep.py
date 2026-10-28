#!/usr/bin/env python3
"""Build the "keep watching" cards for the Fire TV launcher (E:\\tv-home).

Run by Home Assistant (shell_command.tvhome_keep) every ten minutes. Reads Plex On Deck from the
Plex server, downloads the show art and logo into /config/www/tvhome/ and writes
/config/www/tvhome_keep.json, which the launcher polls. The Plex token stays on the HA host
(secrets.yaml keys plex_token, plex_url, ha_base_url); nothing secret is written to the output.

The same run also writes /config/www/tvhome_new.json, the feed behind the rotating tile: what is on
live right now from your XMLTV guides (sport and films first), then the "new on Plex" cards - recent
film releases plus episodes that aired in the last NEW_DAYS, one card per series. Films added purely
to an existing library are filtered out by release year. Images only download again when the list
changes, so a quiet library costs a couple of API calls.

Second card: part-watched Plex movie, else a TiviMate "resume last channel" card.
Optional argument: a base64 string "Title|Season line|netflix title id" adds a manual Netflix card.
Environment overrides (for running from a PC): PLEX_TOKEN, PLEX_URL, WWW_DIR, HA_BASE, PLEX_LIBS, PLEX_COUNT.
"""
import base64, hashlib, json, os, re, sys, time, urllib.error, urllib.parse, urllib.request
import xml.etree.ElementTree as ET
from datetime import date, datetime, timedelta, timezone

SECRETS = os.environ.get("SECRETS", "/config/secrets.yaml")


def secret(key, default=""):
    """Value of `key: value` in HA's secrets.yaml (plex_token, plex_url, ha_base_url)."""
    try:
        for line in open(SECRETS, encoding="utf-8"):
            m = re.match(r'\s*%s:\s*"?([^"\s]+)"?' % re.escape(key), line)
            if m:
                return m.group(1)
    except OSError:
        pass
    return default


PLEX_URL = os.environ.get("PLEX_URL") or secret("plex_url", "http://localhost:32400")
WWW = os.environ.get("WWW_DIR", "/config/www")
HA_BASE = os.environ.get("HA_BASE") or secret("ha_base_url", "http://homeassistant.local:8123")
LIBS = [s.strip() for s in os.environ.get("PLEX_LIBS", "TV Shows").split(",") if s.strip()]
COUNT = int(os.environ.get("PLEX_COUNT", "1"))
# The rotating "new on Plex" tile: every movie/show section by default, or the titles named here.
NEW_LIBS = [s.strip() for s in os.environ.get("PLEX_NEW_LIBS", "").split(",") if s.strip()]
NEW_MOVIES = int(os.environ.get("PLEX_NEW_MOVIES", "5"))      # recent releases only, not old films re-added
NEW_EPISODES = int(os.environ.get("PLEX_NEW_EPISODES", "5"))  # one per series, from what is still airing
NEW_DAYS = int(os.environ.get("PLEX_NEW_DAYS", "60"))         # an episode is new if it aired inside this window
# "Live now" cards come from XMLTV guides - the same data TiviMate draws its own guide from. Comma or
# space separated, and local files work too: EPG_URLS="https://.../epg.xml,/tmp/guide.xml".
EPG_URLS = [u for u in re.split(r"[,\s]+", os.environ.get("EPG_URLS") or secret("epg_urls", "")) if u.strip()]
LIVE_COUNT = int(os.environ.get("LIVE_COUNT", "4"))          # how many "live now" cards to mix in

def token():
    t = os.environ.get("PLEX_TOKEN")
    if t:
        return t
    t = secret("plex_token")
    if t:
        return t
    sys.exit("no plex_token in secrets.yaml")


TOKEN = token()
if not os.environ.get("PLEX_URL") and not secret("plex_url"):
    print("warning: no plex_url in secrets.yaml - using", PLEX_URL)
if not os.environ.get("HA_BASE") and not secret("ha_base_url"):
    print("warning: no ha_base_url in secrets.yaml - using", HA_BASE,
          "(the TV must be able to resolve that name, an IP is safer)")


def get(path, binary=False):
    sep = "&" if "?" in path else "?"
    req = urllib.request.Request(PLEX_URL + path + sep + "X-Plex-Token=" + TOKEN, headers={"Accept": "application/json"})
    data = urllib.request.urlopen(req, timeout=15).read()
    return data if binary else json.loads(data)


def save(path, name):
    try:
        data = get(path, binary=True)
        os.makedirs(os.path.join(WWW, "tvhome"), exist_ok=True)
        open(os.path.join(WWW, "tvhome", name), "wb").write(data)
        return HA_BASE + "/local/tvhome/" + name
    except Exception:
        return ""


def still(url):
    """720x405 crop of a Plex artwork url - what the 16:9 cards are sized for."""
    return "/photo/:/transcode?width=720&height=405&minSize=1&upscale=1&url=" + urllib.parse.quote(url)


def art_url(rk, wanted):
    """Image url of a given type ('background', 'clearLogo') for a library item."""
    try:
        for im in get("/library/metadata/%s" % rk)["MediaContainer"]["Metadata"][0].get("Image", []):
            if im.get("type") == wanted:
                return im.get("url", "")
    except Exception:
        pass
    return ""


def main():
    machine = get("/identity")["MediaContainer"]["machineIdentifier"]
    deck = [m for m in get("/library/onDeck")["MediaContainer"].get("Metadata", [])
            if m.get("type") == "episode" and m.get("librarySectionTitle") in LIBS]
    deck.sort(key=lambda m: 0 if m.get("viewOffset") else 1)  # in progress first, then up next
    items = []
    for i, m in enumerate(deck[:COUNT]):
        show_key = m.get("grandparentRatingKey")
        bg = art_url(show_key, "background") or m.get("grandparentArt", "")
        logo = art_url(show_key, "clearLogo")
        dur, off = m.get("duration") or 0, m.get("viewOffset") or 0
        left = max(0, dur - off) // 60000
        sub = "S%s \u00b7 E%s \u00b7 %s" % (m.get("parentIndex", "?"), m.get("index", "?"), ("%d min left" % left) if off else "Up next")
        items.append({
            "app": "Plex", "pkg": "com.plexapp.android",
            "title": m.get("grandparentTitle", m.get("title", "")), "sub": sub,
            "progress": round(off / dur, 3) if dur and off else 0,
            "image": save("/photo/:/transcode?width=720&height=405&minSize=1&upscale=1&url=" + urllib.parse.quote(bg), "plex%d_bg.jpg" % i) if bg else "",
            "logo": save(logo, "plex%d_logo.png" % i) if logo else "",
            "uri": "plex://server://%s/com.plexapp.plugins.library/library/metadata/%s" % (machine, m["ratingKey"]),
        })
    # Second card: a Plex movie that is part-watched; failing that, TiviMate resumes the last live channel.
    movies = [m for m in get("/library/onDeck")["MediaContainer"].get("Metadata", []) if m.get("type") == "movie" and m.get("viewOffset")]
    if movies:
        m = movies[0]
        bg = art_url(m["ratingKey"], "background") or m.get("art", "")
        logo = art_url(m["ratingKey"], "clearLogo")
        dur, off = m.get("duration") or 0, m.get("viewOffset") or 0
        items.append({
            "app": "Plex", "pkg": "com.plexapp.android", "title": m.get("title", ""),
            "sub": "%s · %d min left" % (m.get("year", ""), max(0, dur - off) // 60000),
            "progress": round(off / dur, 3) if dur else 0,
            "image": save("/photo/:/transcode?width=720&height=405&minSize=1&upscale=1&url=" + urllib.parse.quote(bg), "plex_movie_bg.jpg") if bg else "",
            "logo": save(logo, "plex_movie_logo.png") if logo else "",
            "uri": "plex://server://%s/com.plexapp.plugins.library/library/metadata/%s" % (machine, m["ratingKey"]),
        })
    else:
        items.append({"app": "TiviMate", "pkg": "ar.tvplayer.tv", "title": "Live TV", "sub": "Resume last channel",
                      "progress": 0, "image": "", "logo": ""})
    nf = ""
    if len(sys.argv) > 1 and sys.argv[1]:
        try:
            nf = base64.b64decode(sys.argv[1]).decode("utf-8").strip()
        except Exception:
            nf = ""
    if nf:   # optional manual Netflix card: "Title|Season line|netflix title id"
        parts = (nf.split("|") + ["", "", ""])[:3]
        card = {"app": "Netflix", "pkg": "com.netflix.ninja", "title": parts[0] or "Continue watching",
                "sub": parts[1] or "Open Netflix", "progress": 0, "image": "", "logo": ""}
        if parts[2]:
            card["uri"] = "https://www.netflix.com/title/" + parts[2]
        items.append(card)
    os.makedirs(WWW, exist_ok=True)
    open(os.path.join(WWW, "tvhome_keep.json"), "w", encoding="utf-8").write(json.dumps({"items": items}, ensure_ascii=False))
    print("wrote", len(items), "cards")
    try:
        # A title already in the row above is not "new" to this screen: skip it, or the tile and the
        # resume card next to it show the same artwork side by side.
        write_new(machine, {i.get("title") for i in items if i.get("app") == "Plex"})
    except Exception as e:   # a Plex hiccup here must never cost the keep-watching cards
        print("new media skipped:", e)


# ---------------------------------------------------------------- new on Plex

def minutes(ms):
    m = (ms or 0) // 60000
    if m <= 0:
        return ""
    return "%dh %02dm" % (m // 60, m % 60) if m >= 60 else "%dm" % m


def aired(value):
    """Plex dates are "YYYY-MM-DD" (sometimes with a time); None if there is nothing usable."""
    try:
        return datetime.strptime((value or "")[:10], "%Y-%m-%d").date()
    except Exception:
        return None


def fresh():
    """New releases and new episodes, not merely new files.

    A film counts only if it came out this year or last (a 1994 title added yesterday is a library change,
    not news), and an episode only if it aired inside NEW_DAYS (so old series being imported stay out).
    Episodes are then collapsed onto their series: one card per show, the most recently aired episode."""
    today = date.today()
    movies, episodes = [], []
    try:
        secs = get("/library/sections")["MediaContainer"].get("Directory", [])
    except Exception:
        return movies, episodes
    for sec in secs:
        if sec.get("type") not in ("movie", "show"):
            continue
        if NEW_LIBS and sec.get("title") not in NEW_LIBS:
            continue
        try:
            md = get("/library/sections/%s/recentlyAdded?X-Plex-Container-Start=0&X-Plex-Container-Size=50" % sec["key"])
        except Exception:
            continue
        for m in md["MediaContainer"].get("Metadata", []):
            kind = m.get("type")
            added = m.get("addedAt") or 0
            released = aired(m.get("originallyAvailableAt"))
            if kind == "movie":
                year = m.get("year") or (released.year if released else 0)
                if year and year >= today.year - 1:
                    movies.append((added, m, str(m.get("ratingKey")), True))
            elif kind in ("episode", "season"):
                if not released or (today - released).days > NEW_DAYS:
                    continue
                rk = m.get("grandparentRatingKey")
                if rk:
                    episodes.append((added, m, str(rk), False))

    movies.sort(key=lambda t: t[0], reverse=True)
    # One card per series, newest episode first.
    episodes.sort(key=lambda t: (aired(t[1].get("originallyAvailableAt")) or date.min, t[0]), reverse=True)
    seen, shows = set(), []
    for item in episodes:
        if item[2] in seen:
            continue
        seen.add(item[2])
        shows.append(item)
    return movies[:NEW_MOVIES], shows[:NEW_EPISODES]


def write_new(machine, skip=()):
    """The rotating tile: live now first, then what is new on Plex."""
    cards = []
    movies, episodes = fresh()
    # Interleaved by when Plex imported them, so the tile does not run five films then five episodes.
    for added, m, rk, movie in sorted(movies + episodes, key=lambda t: t[0], reverse=True):
        if ((m.get("title") if movie else m.get("grandparentTitle")) or "") in skip:
            continue
        bg = (m.get("art") if movie else m.get("grandparentArt")) or art_url(rk, "background")
        logo = art_url(rk, "clearLogo")
        if movie:
            sub = " \u00b7 ".join([x for x in [str(m.get("year") or ""), minutes(m.get("duration"))] if x])
        elif m.get("type") == "season":
            sub = m.get("title") or "New season"
        else:
            sub = "S%s \u00b7 E%s \u00b7 New episode" % (m.get("parentIndex", "?"), m.get("index", "?"))
        cards.append({
            "app": "Plex", "pkg": "com.plexapp.android",
            "title": (m.get("title") if movie else m.get("grandparentTitle")) or m.get("title", ""),
            "sub": sub, "progress": 0,
            "uri": "plex://server://%s/com.plexapp.plugins.library/library/metadata/%s" % (machine, rk),
            "_art": still(bg) if bg else "", "_logo": logo, "_plex": True,
        })
    try:
        cards = live_cards() + cards   # what is on now is time-sensitive, so it leads the rotation
    except Exception as e:   # a broken guide must never cost the new-on-Plex cards
        print("live now skipped:", e)
    write_tile(cards)


# ---------------------------------------------------------------- live now (EPG)

# Specific sports, not just the word "sport": a channel's generic "Sports" category is usually a talk
# show or a fishing programme, so it only counts with a live tag, a "v" matchup or a sport name.
SPORT_RE = re.compile(
    r"\b(sport|sports|cricket|rugby|football|soccer|afl|nrl|league|tennis|golf|motorsport|motor racing|"
    r"formula|f1|supercars|racing|boxing|ufc|mma|nba|nfl|nhl|mlb|wnba|netball|hockey|swimming|athletics|"
    r"bowls|darts|snooker|cycling|surfing|skiing|wrestling|volleyball|basketball|baseball)\b", re.I)
SPECIFIC_RE = re.compile(
    r"\b(cricket|rugby|football|soccer|afl|nrl|tennis|golf|motorsport|motor racing|formula|f1|supercars|"
    r"racing|boxing|ufc|mma|nba|nfl|nhl|mlb|wnba|netball|hockey|swimming|athletics|bowls|darts|snooker|"
    r"cycling|surfing|skiing|wrestling|volleyball|basketball|baseball)\b", re.I)
TEAMS_RE = re.compile(r"\s(v|vs|versus|@)\s", re.I)
# Not live events: replays and studio shows. "Countdown" also catches the pre-match shows.
FILLER_RE = re.compile(r"\b(replay|replays|rewind|classic|encore|highlights?|countdown|preview|talk|studio|"
                       r"best of|vault|pre[- ]?game|post[- ]?game)\b", re.I)


def epg_ts(value):
    """XMLTV "20261029060000 +1000" -> epoch seconds (UTC); 0 when there is nothing usable."""
    try:
        dt = datetime.strptime((value or "")[:14], "%Y%m%d%H%M%S")
    except ValueError:
        return 0
    off = (value or "")[15:20].strip()
    if len(off) == 5 and off[0] in "+-":
        sign = -1 if off[0] == "-" else 1
        dt -= timedelta(minutes=sign * (int(off[1:3]) * 60 + int(off[3:5])))
    return int(dt.replace(tzinfo=timezone.utc).timestamp())


def fetch_bytes(url, cap=32 * 1024 * 1024):
    """A guide, cached on disk and re-downloaded only when the server says it changed.

    The iptv4u guide alone is 11 MB; running this every ten minutes would otherwise pull gigabytes a day.
    """
    if "://" not in url:   # a local file, handy for testing a guide before pointing the TV at it
        return open(url, "rb").read()
    base = os.path.join(os.path.dirname(WWW) or ".", ".tvhome_epg")
    path = os.path.join(base, hashlib.sha1(url.encode()).hexdigest()[:12] + ".xml")
    headers = {"User-Agent": "tvhome/1.3"}
    cached = False
    try:
        meta = json.load(open(path + ".json", encoding="utf-8"))
        cached = os.path.exists(path)
        if cached and meta.get("etag"):
            headers["If-None-Match"] = meta["etag"]
        if cached and meta.get("modified"):
            headers["If-Modified-Since"] = meta["modified"]
    except Exception:
        pass
    req = urllib.request.Request(url, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            data = resp.read(cap)
            meta = {"etag": resp.headers.get("ETag", ""), "modified": resp.headers.get("Last-Modified", "")}
    except urllib.error.HTTPError as e:
        if e.code == 304 and cached:
            return open(path, "rb").read()
        raise
    os.makedirs(base, exist_ok=True)
    open(path, "wb").write(data)
    json.dump(meta, open(path + ".json", "w", encoding="utf-8"))
    return data


def epg_image(url, name):
    """A programme still or channel logo, saved next to the Plex art. Failure costs the image, not the card."""
    if not url.startswith("http"):
        return ""
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "tvhome/1.3"})
        data = urllib.request.urlopen(req, timeout=10).read(4 * 1024 * 1024)
        if len(data) < 1000:
            return ""
        os.makedirs(os.path.join(WWW, "tvhome"), exist_ok=True)
        open(os.path.join(WWW, "tvhome", name), "wb").write(data)
        return HA_BASE + "/local/tvhome/" + name
    except Exception:
        return ""


def title_key(title):
    """Dedupe key: the fixture before the "|", without punctuation or a Live: prefix."""
    t = re.sub(r"^live:\s*", "", title.split("|")[0].strip().lower())
    return re.sub(r"[^a-z0-9]+", "", t)


def live_cards():
    """What is on right now, best first: live sport, then a film, from every guide in EPG_URLS."""
    now, found = time.time(), []
    for url in EPG_URLS:
        try:
            root = ET.fromstring(fetch_bytes(url))
        except Exception as e:
            print("guide skipped:", url, e)
            continue
        chans = {}
        for c in root.findall("channel"):
            icon = c.find("icon")
            chans[c.get("id")] = ((c.findtext("display-name") or "").strip(),
                                  icon.get("src", "") if icon is not None else "")
        for p in root.findall("programme"):
            start, stop = epg_ts(p.get("start")), epg_ts(p.get("stop"))
            if not start or not stop or not start <= now < stop or stop - now < 300:
                continue
            channel, ch_icon = chans.get(p.get("channel"), ("", ""))
            if not channel or channel.lower().startswith("24/7"):
                continue
            raw = (p.findtext("title") or "").strip()
            sub = (p.findtext("sub-title") or "").strip()
            desc = (p.findtext("desc") or "").strip()
            cats = [x.text or "" for x in p.findall("category")]
            # "Stan Sport Event 12" style placeholders describe themselves; they are not a programme.
            if not raw or "nothing is listed for this slot" in desc.lower() or "24/7" in cats:
                continue
            if FILLER_RE.search(raw) or FILLER_RE.search(sub):
                continue
            # An old year in the title or subtitle means a classic/replay, not tonight's match.
            years = [int(y) for y in re.findall(r"\b(19\d{2}|20\d{2})\b", raw + " " + sub)]
            if years and max(years) < date.today().year - 2:
                continue
            text = " ".join(cats) + " " + raw
            live_tag, teams = p.find("live") is not None, bool(TEAMS_RE.search(raw))
            sport = bool(SPORT_RE.search(text)) and (bool(SPECIFIC_RE.search(text)) or live_tag or teams)
            movie = p.find("movie") is not None or any("movie" in c.lower() for c in cats)
            if not sport and not movie:
                continue
            dur = (stop - start) / 60
            if dur < (45 if movie and not sport else 20):
                continue
            title = re.sub(r"^live:\s*", "", raw, flags=re.I).split(" | ")[0].strip()
            # A programme still fills the card; with none, the channel logo sits contained on the gradient
            # rather than a bare panel. The launcher is told which one it is, so it does not crop the logo.
            icon = p.find("icon")
            art = (icon.get("src", "") if icon is not None else "") or ch_icon
            card = {
                "kind": "live", "app": "TiviMate", "pkg": "ar.tvplayer.tv",
                "title": title, "caption": "%s \u00b7 until %s" % (channel, datetime.fromtimestamp(stop).strftime("%H:%M")),
                "progress": 0, "uri": "", "_art": art,
                "_backup": ch_icon if art != ch_icon else "",
                "_fit": 0 if (icon is not None and icon.get("src")) else 1,
                "_plex": False, "_sport": sport, "_score": 0, "until": int(stop),
            }
            # Sport outranks a film, and a real fixture outranks a studio show.
            card["_score"] = (10 if sport else 5) + (4 if live_tag else 0) + (2 if teams else 0) \
                + (1 if stop - now >= 1500 else 0) + (1 if dur >= 60 else 0)
            found.append(card)
    found.sort(key=lambda c: -c["_score"])
    seen, unique = set(), []
    for c in found:
        k = title_key(c["title"])
        if k and k not in seen:   # the same match on a simulcast channel only needs one card
            seen.add(k)
            unique.append(c)
    sports = [c for c in unique if c["_sport"]]
    films = [c for c in unique if not c["_sport"]]
    # Leave a slot for a film when one is on: the tile is sport *or* a movie, not four matches.
    take = sports[:max(0, LIVE_COUNT - (1 if films else 0))] + films[:1]
    for c in unique:
        if len(take) >= LIVE_COUNT:
            break
        if c not in take:
            take.append(c)
    take.sort(key=lambda c: (not c["_sport"], -c["_score"]))
    return take


def write_tile(cards):
    """Write the rotation. Artwork is only downloaded when the list changes, and a failed image is
    retried next run without re-fetching the ones that worked."""
    path = os.path.join(WWW, "tvhome_new.json")
    if not cards:   # nothing new and nothing live: tell the launcher to drop the tile
        if os.path.exists(path):
            open(path, "w", encoding="utf-8").write('{"items": []}')
        return
    sig = json.dumps([[c.get("kind", ""), c["title"], c.get("until", 0), c.get("uri", ""),
                       c.get("_art", ""), c.get("_logo", "")] for c in cards])
    old = {}
    try:
        old = json.load(open(path, encoding="utf-8"))
    except Exception:
        pass
    same = old.get("sig") == sig
    prev = old.get("items", []) if same else []
    items = []
    for i, c in enumerate(cards):
        art, logo = c.pop("_art", ""), c.pop("_logo", "")
        backup = c.pop("_backup", "")
        plex = c.pop("_plex", False)
        if c.pop("_fit", 0):
            c["artfit"] = 1
        c.pop("_sport", None)
        c.pop("_score", None)
        was = prev[i] if i < len(prev) else {}
        if was.get("image"):   # same list, same index: the file is already on disk
            c["image"] = was["image"]
            if was.get("artfit"):   # a logo fallback from an earlier run keeps its contained fit
                c["artfit"] = 1
        elif art:
            c["image"] = save(still(art), "new%d_bg.jpg" % i) if plex else epg_image(art, "live%d_bg.jpg" % i)
            if not c["image"] and backup:   # dead still url: use the channel logo instead
                c["image"] = epg_image(backup, "live%d_bg.jpg" % i)
                if c["image"]:
                    c["artfit"] = 1
        else:
            c["image"] = ""
        c["logo"] = was.get("logo") or (save(logo, "new%d_logo.png" % i) if logo else "")
        items.append(c)
    if same and old.get("items") == items:
        print("tile unchanged")
        return
    os.makedirs(WWW, exist_ok=True)
    open(path, "w", encoding="utf-8").write(json.dumps({"sig": sig, "items": items}, ensure_ascii=False))
    live = sum(1 for i in items if i.get("kind") == "live")
    print("wrote", len(items), "tile cards (%d live, %d new on Plex)" % (live, len(items) - live))


if __name__ == "__main__":
    main()
