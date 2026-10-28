#!/usr/bin/env python3
"""Download free wallpapers into launcher/assets/wallpapers and record credits.

Source (free, no account or API key). Add your own .jpg files to the folder too; anything 1920x1080 or larger works:
  nasa  NASA Image and Video Library (public domain)        - space, Earth from orbit (opt-in)

The shipped set is nature and art only; --nasa defaults to 0 so running this does not bring space
imagery back. Pass --nasa N if you want it.

Usage: python tools/fetch_wallpapers.py [--nasa N] [--out launcher/assets/wallpapers]
Then rebuild the APK. Delete any picture you do not like before building.
"""
import argparse, json, os, re, sys, urllib.parse, urllib.request

UA = {"User-Agent": "tvhome-wallpapers/1.0 (personal TV launcher)"}


def get(url, binary=False):
    data = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read()
    return data if binary else json.loads(data)


def save(out, name, data):
    path = os.path.join(out, name)
    open(path, "wb").write(data)
    return path


def credit(out, line):
    f = os.path.join(os.path.dirname(out.rstrip("/\\")), "WALLPAPER-CREDITS.md")
    new = not os.path.exists(f)
    with open(f, "a", encoding="utf-8") as fh:
        if new:
            fh.write("# Wallpaper credits\n\n")
        fh.write(line + "\n")


def nasa(out, n):
    got = 0
    for q in ("nebula", "galaxy hubble", "earth from space", "aurora from space", "milky way"):
        if got >= n:
            break
        res = get("https://images-api.nasa.gov/search?media_type=image&page_size=40&q=" + urllib.parse.quote(q))
        for it in res["collection"]["items"]:
            if got >= n:
                break
            meta = it["data"][0]
            try:
                assets = get(it["href"])
            except Exception:
                continue
            big = [a for a in assets if a.endswith("~large.jpg")] or [a for a in assets if a.endswith("~medium.jpg")]
            if not big:
                continue
            try:
                data = get(big[0].replace("http://", "https://"), binary=True)
            except Exception:
                continue
            if not 150_000 < len(data) < 4_000_000:
                continue
            name = "nasa-%02d.jpg" % (got + 1)
            save(out, name, data)
            credit(out, "- `%s` %s: NASA (%s), public domain. https://images.nasa.gov/details/%s"
                   % (name, meta.get("title", "").strip(), meta.get("center") or "NASA", meta["nasa_id"]))
            got += 1
            print("nasa", name, meta.get("title", "")[:60])


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--nasa", type=int, default=0)
    ap.add_argument("--out", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "launcher", "assets", "wallpapers"))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    for fn, n in ((nasa, a.nasa),):
        if n:
            try:
                fn(a.out, n)
            except Exception as e:
                print(fn.__name__, "failed:", e, file=sys.stderr)
