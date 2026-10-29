"""Wait for the Stick's adbd, then install the stick build.

The Stick accepts TCP on 5555 but sends no ADB greeting while its adbd is wedged.
This polls until it answers, then installs, verifies the md5 and screenshots.

Throwaway helper: lives outside build/ because building wipes that directory.
"""
import hashlib
import importlib.util
import os
import sys
import time

HOST = "192.168.1.251"
APK = os.path.join("launcher", "out", "tvhome-hurley-stick.apk")
PKG = "com.mihir.tvhome"
WAIT_S = int(os.environ.get("WAIT_S", "1800"))
DEADLINE = time.time() + WAIT_S


def log(msg):
    print(f"{time.strftime('%H:%M:%S')} {msg}", flush=True)


os.environ["TV_HOST"] = HOST
spec = importlib.util.spec_from_file_location("tv", "tools/tv.py")
tv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tv)
from adb_shell.adb_device import AdbDeviceTcp


def connect():
    dev = AdbDeviceTcp(tv.HOST, tv.PORT, default_transport_timeout_s=5.0)
    dev.connect(rsa_keys=[tv.signer()], auth_timeout_s=5)
    return dev


log(f"waiting for adbd on {HOST}:5555 (up to {WAIT_S // 60} min)")
dev = None
while time.time() < DEADLINE:
    try:
        dev = connect()
        break
    except Exception as exc:
        log(f"  no answer ({type(exc).__name__})")
        time.sleep(3)

if dev is None:
    log("gave up: adbd never answered")
    sys.exit(1)

try:
    log(f"adbd awake: {dev.shell('getprop ro.product.model').strip()}")

    local_md5 = hashlib.md5(open(APK, "rb").read()).hexdigest()
    tv.push_b64(dev, APK, "/data/local/tmp/tvhome.apk")
    log(dev.shell("pm install -r /data/local/tmp/tvhome.apk", read_timeout_s=300).strip())
    dev.shell("rm -f /data/local/tmp/tvhome.apk", read_timeout_s=60)

    installed = dev.shell(
        f"md5sum $(pm path {PKG} | sed 's/package://' | tr -d '\\r')", read_timeout_s=60
    ).split()
    remote_md5 = installed[0] if installed else "?"
    log(f"installed apk md5 {remote_md5} | local {local_md5} | match={remote_md5 == local_md5}")

    log(dev.shell(f"dumpsys package {PKG} | grep -E 'versionName|lastUpdateTime'").strip())
    dev.shell(f"am force-stop {PKG}")
    log(dev.shell(f"am start -n {PKG}/.MainActivity").strip())
    time.sleep(5)
    dev.shell("screencap -p /sdcard/_shot.png")
    dev.pull("/sdcard/_shot.png", os.path.join("launcher", "build", "stick-home.png"))
    dev.shell("rm -f /sdcard/_shot.png")
    log("screenshot -> launcher/build/stick-home.png")
    log("DONE")
finally:
    try:
        dev.close()
    except Exception:
        pass
