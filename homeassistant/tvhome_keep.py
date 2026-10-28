#!/usr/bin/env python3
"""Build the "keep watching" cards for the Fire TV launcher (E:\\tv-home).

Run by Home Assistant (shell_command.tvhome_keep) every 15 minutes. Reads Plex On Deck from the
Plex server, downloads the show art and logo into /config/www/tvhome/ and writes
/config/www/tvhome_keep.json, which the launcher polls. The Plex token stays on the HA host
(secrets.yaml keys plex_token, plex_url, ha_base_url); nothing secret is written to the output.

The same run also writes /config/www/tvhome_new.json, the "new on Plex" feed behind the rotating
tile: recent film releases plus episodes that aired in the last NEW_DAYS, one card per series.
Films added purely to an existing library are filtered out by release year. Images only download
again when the list changes, so a quiet library costs a couple of API calls.

Second card: part-watched Plex movie, else a TiviMate "resume last channel" card.
Optional argument: a base64 string "Title|Season line|netflix title id" adds a manual Netflix card.
Environment overrides (for running from a PC): PLEX_TOKEN, PLEX_URL, WWW_DIR, HA_BASE, PLEX_LIBS, PLEX_COUNT.
"""
import base64, json, os, re, sys, urllib.parse, urllib.request
from datetime import date, datetime

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
            "title": (m.get("title") if movie else m.get("grandparentTitle")) or m.get("title", ""),
            "sub": sub, "bg": bg, "logo": logo,
            "uri": "plex://server://%s/com.plexapp.plugins.library/library/metadata/%s" % (machine, rk),
        })
    path = os.path.join(WWW, "tvhome_new.json")
    if not cards:   # nothing new outside what is already on the row: tell the launcher to drop the tile
        if os.path.exists(path):
            open(path, "w", encoding="utf-8").write('{"items": []}')
        return
    try:   # unchanged library: keep the existing file (and its images), skip every download
        if [c.get("uri") for c in json.load(open(path, encoding="utf-8")).get("items", [])] == [c["uri"] for c in cards]:
            print("new media unchanged")
            return
    except Exception:
        pass
    items = [{"app": "Plex", "pkg": "com.plexapp.android", "title": c["title"], "sub": c["sub"],
              "progress": 0, "uri": c["uri"],
              "image": save(still(c["bg"]), "new%d_bg.jpg" % i) if c["bg"] else "",
              "logo": save(c["logo"], "new%d_logo.png" % i) if c["logo"] else ""}
             for i, c in enumerate(cards)]
    os.makedirs(WWW, exist_ok=True)
    open(path, "w", encoding="utf-8").write(json.dumps({"items": items}, ensure_ascii=False))
    print("wrote", len(items), "new-media cards")


if __name__ == "__main__":
    main()
