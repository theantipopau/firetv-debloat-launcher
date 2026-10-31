#!/usr/bin/env python3
"""Stage tizen/ as a Tizen web widget, sign it, and put it on the TV.

    python tools/package-tizen.py --check
        stage + validate only; no Tizen Studio needed.

    python tools/package-tizen.py --snapshot-url http://192.168.1.10:8123/local/tvhome.json
        stage, then sign with Tizen Studio, producing build/TVHome.wgt.

    python tools/package-tizen.py --snapshot-url ... --install 192.168.1.45
        sign, connect sdb, install, and launch it.

    python tools/package-tizen.py --uninstall 192.168.1.45
        remove it again (rollback).

The staged folder is what gets signed, so the repo copy can keep pointing at
the sample file while the TV gets your Home Assistant URL. Only the files the
widget needs are copied: no README, no sample snapshot, no Tizen IDE metadata
(Tizen refuses packages that carry it).

Signing needs Tizen Studio with the TV extensions and a Samsung certificate
profile; the TV needs developer mode. tizen/INSTALL.md is the walkthrough.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(ROOT, "tizen")
BUILD = os.path.join(ROOT, "build", "tizen")
WGT = os.path.join(ROOT, "build", "TVHome.wgt")

# Must match <tizen:application> in tizen/config.xml.
PACKAGE = "TVHOME0001"
APP_ID = PACKAGE + ".TVHome"

# Everything the widget ships, by name. Anything else in tizen/ is for humans.
FILES = ["config.xml", "index.html", "style.css", "dashboard.js", "config.js", "icon.png"]
DIRS = ["wallpapers"]

WIDGET_NS = "{http://www.w3.org/ns/widgets}"
TIZEN_NS = "{http://tizen.org/ns/widgets}"

TIZEN_DIRS = [
    os.environ.get("TIZEN_STUDIO", ""),
    os.path.expanduser("~/tizen-studio"),
    "C:/tizen-studio",
    "C:/Program Files/tizen-studio",
    "/opt/tizen-studio",
]


def die(msg, code=1):
    print("\n" + msg.rstrip(), file=sys.stderr)
    sys.exit(code)


def find_tool(name, studio_rel):
    found = shutil.which(name)
    if found:
        return found
    if os.name == "nt":
        found = shutil.which(name + ".bat")
        if found:
            return found
    for base in TIZEN_DIRS:
        if base:
            p = os.path.join(base, studio_rel)
            if os.path.exists(p):
                return p
    return None


def find_tizen():
    return find_tool("tizen", os.path.join("tools", "ide", "bin",
                                           "tizen.bat" if os.name == "nt" else "tizen"))


def find_sdb():
    return find_tool("sdb", os.path.join("tools", "sdb.exe" if os.name == "nt" else "sdb"))


def png_size(path):
    with open(path, "rb") as f:
        head = f.read(24)
    if head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    import struct
    return struct.unpack(">II", head[16:24])


def snapshot_line(js, key="snapshotUrl"):
    """Index of the real `<key>:` line, skipping the /* ... */ examples above it."""
    in_comment = False
    for i, line in enumerate(js.splitlines()):
        if in_comment:
            if "*/" in line:
                in_comment = False
            continue
        if "/*" in line:
            if "*/" not in line.split("/*", 1)[1]:
                in_comment = True
            continue
        if re.match(r"^\s*" + key + r":", line):
            return i
    return -1


def snapshot_url_in(js):
    i = snapshot_line(js)
    if i < 0:
        return None
    m = re.search(r'snapshotUrl:\s*"([^"]*)"', js.splitlines()[i])
    return m.group(1) if m else None


def set_snapshot(js, url):
    i = snapshot_line(js)
    if i < 0:
        die("tizen/config.js has no snapshotUrl line to replace")
    lines = js.splitlines(True)
    lines[i] = re.sub(r'(snapshotUrl:\s*)"[^"]*"',
                      lambda m: m.group(1) + json.dumps(url),
                      lines[i], count=1)
    return "".join(lines)


def set_value(js, key, value):
    """One string setting in the staged config.js: the household's name, or the source tiles'
    key URL (an HA webhook id is a secret, so it never goes into the repo copy)."""
    i = snapshot_line(js, key)
    if i < 0:
        die("tizen/config.js has no %s line to replace" % key)
    lines = js.splitlines(True)
    lines[i] = re.sub(r'(' + key + r':\s*)"[^"]*"', lambda m: m.group(1) + json.dumps(value),
                      lines[i], count=1)
    return "".join(lines)


def stage(url, control=None, family=None):
    # Rebuild the staged project from scratch; never leave a stale .wgt behind.
    shutil.rmtree(BUILD, ignore_errors=True)
    if os.path.exists(WGT):
        os.remove(WGT)
    os.makedirs(BUILD)

    for name in FILES:
        shutil.copy2(os.path.join(SRC, name), os.path.join(BUILD, name))
    for name in DIRS:
        shutil.copytree(os.path.join(SRC, name), os.path.join(BUILD, name))

    cfg_path = os.path.join(BUILD, "config.js")
    with open(cfg_path, encoding="utf-8") as f:
        cfg = f.read()

    if url:
        if not re.match(r"^https?://", url):
            die("--snapshot-url must start with http:// or https://")
        cfg = set_snapshot(cfg, url)
    if control:
        if not re.match(r"^https?://", control):
            die("--control-url must start with http:// or https://")
        cfg = set_value(cfg, "controlUrl", control)
    if family:
        cfg = set_value(cfg, "family", family)
    if url or control or family:
        with open(cfg_path, "w", encoding="utf-8") as f:
            f.write(cfg)

    return snapshot_url_in(cfg)


def check(url):
    problems = []

    for name in FILES:
        if not os.path.isfile(os.path.join(BUILD, name)):
            problems.append("missing " + name)

    try:
        tree = ET.parse(os.path.join(BUILD, "config.xml"))
    except ET.ParseError as e:
        die("config.xml is not valid XML: %s" % e)

    root = tree.getroot()
    app = root.find(TIZEN_NS + "application")
    if app is None:
        problems.append("config.xml has no <tizen:application>")
    else:
        if app.get("package") != PACKAGE:
            problems.append("config.xml package %r, expected %r" % (app.get("package"), PACKAGE))
        if app.get("id") != APP_ID:
            problems.append("config.xml app id %r, expected %r" % (app.get("id"), APP_ID))
        if not re.match(r"^[A-Za-z][A-Za-z0-9]{9}$", app.get("package") or ""):
            problems.append("Tizen package id must be 10 letters/digits")

    profile = root.find(TIZEN_NS + "profile")
    if profile is None or not profile.get("name"):
        problems.append("config.xml has no <tizen:profile>")

    privs = [p.get("name") for p in root.findall(TIZEN_NS + "privilege")]
    for need in ("internet", "application.launch", "application.info"):
        if not any(p and p.endswith("/" + need) for p in privs):
            problems.append("config.xml lacks the %s privilege" % need)

    content = root.find(WIDGET_NS + "content")
    if content is None or not os.path.isfile(os.path.join(BUILD, content.get("src", ""))):
        problems.append("config.xml <content src> does not exist in the package")

    icon = root.find(WIDGET_NS + "icon")
    if icon is None or not os.path.isfile(os.path.join(BUILD, icon.get("src", ""))):
        problems.append("config.xml <icon src> does not exist in the package")
    else:
        size = png_size(os.path.join(BUILD, icon.get("src")))
        if not size or size[0] != size[1] or size[0] < 256:
            problems.append("icon.png should be a square PNG of at least 256 px (got %s)" % (size,))

    with open(os.path.join(BUILD, "config.js"), encoding="utf-8") as f:
        cfg = f.read()
    for path in re.findall(r'"(wallpapers/[^"]+)"', cfg):
        if not os.path.isfile(os.path.join(BUILD, path)):
            problems.append("config.js points at %s, which is not in the package" % path)

    for name in os.listdir(BUILD):
        if name.endswith(".wgt") or name in (".project", ".tproject", ".settings",
                                             "author-signature.xml", "signature1.xml"):
            problems.append("%s does not belong in the package" % name)

    if os.path.exists(os.path.join(BUILD, "data")):
        problems.append("data/ is in the package; the TV must not fall back to sample data")

    # A widget cannot reach outside its own folder.
    for name in FILES:
        if not name.endswith((".js", ".css", ".html")):
            continue
        with open(os.path.join(BUILD, name), encoding="utf-8", errors="replace") as f:
            text = f.read()
        if "../" in text:
            problems.append("%s still uses a ../ path" % name)

    total = sum(os.path.getsize(os.path.join(dp, f))
                for dp, _, fs in os.walk(BUILD) for f in fs)

    if problems:
        print("package is not valid:")
        for p in problems:
            print("  - " + p)
        sys.exit(2)

    print("staged %s -> %s" % (os.path.relpath(SRC, ROOT), os.path.relpath(BUILD, ROOT)))
    print("  %d files, %.1f MB" % (sum(len(fs) for _, _, fs in os.walk(BUILD)), total / 1e6))
    print("  snapshotUrl: %s" % (url or "(empty - the widget will say 'no data' until you set it)"))
    print("  sample snapshot: not shipped")
    if not url:
        print("  hint: pass --snapshot-url so the TV reads Home Assistant without editing the repo")


def run(cmd, cwd=None, quiet=False):
    if not quiet:
        print("$ " + " ".join('"%s"' % c if " " in c else c for c in cmd))
    try:
        out = subprocess.run(cmd, cwd=cwd, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, text=True)
    except OSError as e:
        die("could not run %s: %s" % (cmd[0], e))
    text = (out.stdout or "").strip()
    if text:
        print(text)
    return out.returncode


def sign(profile):
    tizen = find_tizen()
    if not tizen:
        die("Tizen CLI not found.\n"
            "Install Tizen Studio with the TV extensions, or set TIZEN_STUDIO to its\n"
            "folder. The staged project is ready at build/tizen; tizen/INSTALL.md shows\n"
            "both the GUI and the CLI routes.", 3)

    code = run([tizen, "package", "-t", "wgt", "-s", profile, "--", BUILD], cwd=BUILD)
    wgts = [os.path.join(dp, f) for dp, _, fs in os.walk(BUILD) for f in fs if f.endswith(".wgt")]
    if code != 0 or not wgts:
        die("signing failed. Usual causes: the certificate profile name is wrong, the\n"
            "profile is not a Samsung certificate, or the TV extensions are missing.\n"
            "Recreate the profile in Certificate Manager (Samsung type), then retry.", 4)

    newest = max(wgts, key=os.path.getmtime)
    shutil.copy2(newest, WGT)
    if newest != WGT:
        os.remove(newest)
    print("signed: %s (%.1f MB)" % (os.path.relpath(WGT, ROOT), os.path.getsize(WGT) / 1e6))
    return WGT


def sdb_for(tv):
    sdb = find_sdb()
    if not sdb:
        die("sdb not found (it ships with Tizen Studio).", 3)
    print("$ sdb connect %s:26101" % tv)
    code = run([sdb, "connect", tv + ":26101"], quiet=True)
    if code != 0:
        die("sdb could not connect. Developer mode must be on (Apps -> 12345 -> Host PC\n"
            "IP -> On), then reboot the TV; port 26101 stays closed until it is.", 5)
    return sdb


def install(tv, wgt, launch):
    tizen = find_tizen()
    if not tizen:
        die("Tizen CLI not found; cannot install.", 3)
    if not os.path.isfile(wgt):
        die("no signed package at %s" % os.path.relpath(wgt, ROOT), 4)
    sdb_for(tv)
    code = run([tizen, "install", "-n", wgt, "-s", tv + ":26101", "--", BUILD])
    if code != 0:
        die("install failed. If the TV complains about the certificate, open Tizen\n"
            "Studio's Device Manager, right-click the TV, and run \"Permit to install apps\".", 6)
    print("installed as %s" % APP_ID)
    if launch:
        run([tizen, "run", "-p", APP_ID, "-s", tv + ":26101"])


def uninstall(tv):
    tizen = find_tizen()
    if not tizen:
        die("Tizen CLI not found; cannot uninstall.", 3)
    sdb_for(tv)
    code = run([tizen, "uninstall", "-p", PACKAGE, "-s", tv + ":26101"])
    print("removed" if code == 0 else "uninstall failed; the TV may be off or the app id changed")


def main():
    ap = argparse.ArgumentParser(description="Stage and sign the Tizen dashboard widget.")
    ap.add_argument("--check", action="store_true", help="stage and validate only; do not sign")
    ap.add_argument("--snapshot-url", metavar="URL",
                    help="Home Assistant tvhome.json URL baked into the staged copy")
    ap.add_argument("--control-url", metavar="URL",
                    help="where the Fire TV / Live TV tiles post their key (an HA webhook)")
    ap.add_argument("--family", metavar="NAME", help='the household in "Good evening, NAME"')
    ap.add_argument("--profile", metavar="NAME",
                    default=os.environ.get("TIZEN_PROFILE", ""),
                    help="Tizen certificate profile name (or TIZEN_PROFILE)")
    ap.add_argument("--install", metavar="TV_IP", help="install on this TV after signing")
    ap.add_argument("--run", action="store_true", help="launch the app after installing")
    ap.add_argument("--uninstall", metavar="TV_IP", help="remove the app from this TV")
    args = ap.parse_args()

    if args.uninstall:
        uninstall(args.uninstall)
        return

    url = stage(args.snapshot_url, args.control_url, args.family)
    check(url)

    if args.check:
        print("\nstaged only. Re-run without --check to sign, or open build/tizen in Tizen Studio.")
        return

    if not args.profile:
        die("--profile is required to sign (Tizen Studio certificate profile name),\n"
            "or set TIZEN_PROFILE. --check still stages and validates without it.", 2)

    sign(args.profile)
    if args.install:
        install(args.install, WGT, args.run)


if __name__ == "__main__":
    main()
