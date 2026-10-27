#!/usr/bin/env python3
"""Build the "keep watching" cards for the Fire TV launcher (E:\\tv-home).

Run by Home Assistant (shell_command.tvhome_keep) every 15 minutes. Reads Plex On Deck from the
Plex server, downloads the show art and logo into /config/www/tvhome/ and writes
/config/www/tvhome_keep.json, which the launcher polls. The Plex token stays on the HA host
(secrets.yaml keys plex_token, plex_url, ha_base_url); nothing secret is written to the output.

Second card: part-watched Plex movie, else a TiviMate "resume last channel" card.
Optional argument: a base64 string "Title|Season line|netflix title id" adds a manual Netflix card.
Environment overrides (for running from a PC): PLEX_TOKEN, PLEX_URL, WWW_DIR, HA_BASE, PLEX_LIBS, PLEX_COUNT.
"""
import base64, json, os, re, sys, urllib.parse, urllib.request

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

def token():
    t = os.environ.get("PLEX_TOKEN")
    if t:
        return t
    t = secret("plex_token")
    if t:
        return t
    sys.exit("no plex_token in secrets.yaml")


TOKEN = token()


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


if __name__ == "__main__":
    main()
