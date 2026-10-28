#!/usr/bin/env python3
"""Tiny ADB-over-Wi-Fi client (no adb binary needed; avoids macOS Local Network blocking).

    tv.py --probe                            (TV_HOST env or tools/.tv_host)
    tv.py 'pm list packages'                 one shell command
    tv.py --batch < cmds.txt                 one command per line, same connection
    tv.py --pull /sdcard/x.png x.png
    tv.py --push-b64 local.apk /data/local/tmp/x.apk   chunked base64 upload with md5 check

Under Git Bash, run with MSYS_NO_PATHCONV=1 for the --push-b64/--pull arguments: otherwise the
shell rewrites /data/... into a Windows path and the upload lands nowhere.
    tv.py --shot out.png                     screenshot from the TV
"""
import base64
import hashlib
import os
import sys

from adb_shell.adb_device import AdbDeviceTcp
from adb_shell.auth.keygen import keygen
from adb_shell.auth.sign_pythonrsa import PythonRSASigner

HERE = os.path.dirname(os.path.abspath(__file__))
HOST_FILE = os.path.join(HERE, ".tv_host")  # written by find-tv.sh, git-ignored
HOST = os.environ.get("TV_HOST") or (open(HOST_FILE).read().strip() if os.path.exists(HOST_FILE) else "")
if not HOST:
    sys.exit("Don't know your TV's IP. Run tools/find-tv.sh, or set TV_HOST=192.168.x.y")
PORT = int(os.environ.get("TV_PORT", "5555"))
KEY = os.path.join(HERE, "adbkey", "adbkey")


def signer():
    os.makedirs(os.path.dirname(KEY), exist_ok=True)
    if not os.path.exists(KEY):
        keygen(KEY)
    return PythonRSASigner(open(KEY + ".pub").read(), open(KEY).read())


def connect():
    dev = AdbDeviceTcp(HOST, PORT, default_transport_timeout_s=120.0)
    dev.connect(rsa_keys=[signer()], auth_timeout_s=120)
    return dev


def push_b64(dev, local, remote, chunk=20_000):
    """Upload over the shell in base64 chunks. 20k per write, because larger echo/printf
    payloads were arriving truncated (which left an old file in place and let a later
    `pm install` quietly install the previous build). Size and md5 are both checked here."""
    data = open(local, "rb").read()
    enc = base64.b64encode(data).decode()
    tmp = remote + ".b64"
    dev.shell(f"rm -f {tmp} {remote}", read_timeout_s=60)
    for i in range(0, len(enc), chunk):
        out = dev.shell(f"printf '%s' '{enc[i:i + chunk]}' >> {tmp}", read_timeout_s=60)
        if out.strip():
            sys.exit(f"upload failed at byte {i}: {out.strip()[:200]}")
    size = dev.shell(f"wc -c < {tmp}", read_timeout_s=60).strip()
    if size != str(len(enc)):
        sys.exit(f"upload incomplete: {size} of {len(enc)} base64 bytes")
    dev.shell(f"base64 -d {tmp} > {remote} && rm -f {tmp}", read_timeout_s=180)
    remote_md5 = dev.shell(f"md5sum {remote}", read_timeout_s=60).split()[0]
    local_md5 = hashlib.md5(data).hexdigest()
    if remote_md5 != local_md5:
        sys.exit(f"md5 mismatch: {remote_md5} != {local_md5}")
    print(f"pushed {local} -> {remote} ({len(data)} bytes, md5 ok)")


def main():
    a = sys.argv[1:]
    dev = connect()
    if not a or a == ["--probe"]:
        for p in ("ro.product.manufacturer", "ro.product.brand", "ro.product.model", "ro.product.device",
                  "ro.build.version.release", "ro.build.version.sdk", "ro.build.display.id"):
            print(f"{p} = {dev.shell(f'getprop {p}').strip()}")
    elif a[0] == "--batch":
        for line in sys.stdin:
            line = line.strip()
            if line and not line.startswith("#"):
                print(f"$ {line}\n{dev.shell(line, read_timeout_s=120).rstrip()}")
    elif a[0] == "--pull":
        dev.pull(a[1], a[2])
    elif a[0] == "--push-b64":
        push_b64(dev, a[1], a[2])
    elif a[0] == "--shot":
        dev.shell("screencap -p /sdcard/_shot.png")
        dev.pull("/sdcard/_shot.png", a[1])
        dev.shell("rm /sdcard/_shot.png")
        print(f"saved {a[1]}")
    else:
        print(dev.shell(" ".join(a), read_timeout_s=120).rstrip())
    dev.close()


if __name__ == "__main__":
    main()
