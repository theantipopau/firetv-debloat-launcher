"""Depth clock masks: where the scenery stands in front of the home screen's clock.

For each wallpaper, finds the skyline across the clock's corner of the photo (walking down each
column until the colour stops being sky) and writes the land below it as an alpha mask:
launcher/assets/depth/<name>.png, a mask (in its alpha) of REGION in photo pixels. The launcher draws that
part of the photo again over the clock, so a ridge or a treeline passes in front of the digits.

A photo gets a mask only when the scenery covers some of the digits but not too many: a clock
behind a wall of trees is unreadable, and one in open sky has nothing to stand behind.

    python tools/make-depth.py              write masks for the photos that qualify
    python tools/make-depth.py --preview D  also write D/depth-preview.png to look at
"""
import glob
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.join(os.path.dirname(__file__), "..", "launcher", "assets")
# The photo pixels the clock can land on, whatever the drift and the parallax; MainActivity.DEPTH_*.
REGION = (80, 60, 480, 260)
# The digits, in photo pixels, at the middle of the slow zoom: what "covered" is measured against.
DIGITS = (166, 136, 392, 205)
MIN_COVER, MAX_COVER = 0.04, 0.40
STEP = 26          # colour distance that ends the sky
LAND = 12          # rows the land must hold for: a thin cloud wisp is back to sky by then
ONLY = sys.argv[sys.argv.index("--only") + 1].split(",") if "--only" in sys.argv else None


def skyline_mask(photo):
    x0, y0, x1, y1 = REGION
    a = np.asarray(photo.crop(REGION).convert("RGB"), dtype=np.float32)
    h, w, _ = a.shape
    line = np.full(w, h)
    for x in range(w):
        ref = a[0:4, x].mean(axis=0)
        for y in range(4, h - LAND):
            if np.linalg.norm(a[y, x] - ref) <= STEP:
                ref = ref * 0.9 + a[y, x] * 0.1   # the sky shades as it nears the horizon
                continue
            # Land, not cloud: it stays off the sky's colour all the way down, and it is darker.
            below = a[y:y + LAND, x]
            if (np.linalg.norm(below - ref, axis=1) > STEP).all() and below.mean() < ref.mean() - 15:
                line[x] = y
                break
    # A lone column that stops early is a bird or a speck of noise, not land.
    line = np.array([int(np.median(line[max(0, x - 2):x + 3])) for x in range(w)])
    # Land is wide: a run of a few columns is a streak in the sky.
    x = 0
    while x < w:
        if line[x] < h:
            end = x
            while end < w and line[end] < h:
                end += 1
            if end - x < 24:
                line[x:end] = h
            x = end
        else:
            x += 1
    m = np.zeros((h, w), np.uint8)
    for x in range(w):
        m[line[x]:, x] = 255
    return Image.fromarray(m).filter(ImageFilter.GaussianBlur(0.8))


def cover(mask):
    x0, y0 = REGION[:2]
    d = np.asarray(mask.crop((DIGITS[0] - x0, DIGITS[1] - y0, DIGITS[2] - x0, DIGITS[3] - y0)))
    return float((d > 127).mean())


def main():
    out = os.path.join(ROOT, "depth")
    os.makedirs(out, exist_ok=True)
    rows = []
    for f in sorted(glob.glob(os.path.join(ROOT, "wallpapers", "*.jpg"))):
        name = os.path.splitext(os.path.basename(f))[0]
        if ONLY and name not in ONLY:
            continue
        photo = Image.open(f).convert("RGB")
        mask = skyline_mask(photo)
        c = cover(mask)
        ok = MIN_COVER <= c <= MAX_COVER
        target = os.path.join(out, name + ".png")
        if ok:   # white with the mask as alpha: Android decodes it straight to a mask
            Image.merge("LA", (Image.new("L", mask.size, 255), mask)).save(target, optimize=True)
        elif os.path.exists(target):
            os.remove(target)
        print("%s cover %3.0f%% %s" % (name, c * 100, "mask" if ok else "-"))
        rows.append((name, photo, mask, c, ok))
    if "--preview" in sys.argv:
        preview(rows, sys.argv[sys.argv.index("--preview") + 1])


def preview(rows, folder):
    """The clock corner of each photo with white digits behind the masked land."""
    try:
        font = ImageFont.truetype("arial.ttf", 96)
    except OSError:
        font = ImageFont.load_default()
    w, h = REGION[2] - REGION[0], REGION[3] - REGION[1]
    sheet = Image.new("RGB", (4 * w, ((len(rows) + 3) // 4) * h))
    for i, (name, photo, mask, c, ok) in enumerate(rows):
        tile = photo.crop(REGION)
        d = ImageDraw.Draw(tile)
        d.text((DIGITS[0] - REGION[0], DIGITS[1] - REGION[1] - 18), "06:20", fill="white", font=font)
        if ok:
            tile.paste(photo.crop(REGION), (0, 0), mask)
        d.text((6, 4), "%s %d%%%s" % (name, c * 100, " MASK" if ok else ""), fill="yellow")
        sheet.paste(tile, ((i % 4) * w, (i // 4) * h))
    sheet.save(os.path.join(folder, "depth-preview.png"))


main()
