"""Install the launcher on a Fire TV over ADB and restart it, so the Home key works afterwards.

Fire OS only re-attaches a package's accessibility service at boot: an install (or a force-stop)
that is not followed by a reboot leaves Home on Amazon's launcher, with no visible cause. That is
the whole reason this script exists rather than a one-line `pm install -r`.

    python tools/install-tv.py launcher/out/tvhome-hurley-stick.apk --host 192.168.1.251
    python tools/install-tv.py launcher/out/tvhome-hurley-cube.apk  --host 192.168.1.252

Use --no-reboot when the TV is mid-use and you can reboot it yourself before pressing Home.
"""
import argparse
import hashlib
import importlib.util
import os
import sys
import time

PKG = "com.mihir.tvhome"
SERVICE = f"{PKG}/{PKG}.HomeKeyService"


def load_tv(host):
    os.environ["TV_HOST"] = host
    here = os.path.dirname(os.path.abspath(__file__))
    spec = importlib.util.spec_from_file_location("tv", os.path.join(here, "tv.py"))
    tv = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(tv)
    return tv


def shell(dev, cmd, timeout=120):
    out = dev.shell(cmd, read_timeout_s=timeout)
    print(f"$ {cmd}\n{out.rstrip()}")
    return out


def wait_home(tv, minutes=4):
    """After the reboot: wait for the box, press Home, and say whether the launcher took it."""
    deadline = time.time() + minutes * 60
    time.sleep(60)
    while time.time() < deadline:
        try:
            dev = tv.connect()
            if dev.shell("getprop sys.boot_completed").strip() == "1":
                break
            dev.close()
        except Exception:
            pass
        time.sleep(5)
    else:
        print("The TV did not come back in time; press Home once it is up.")
        return False
    try:
        time.sleep(20)   # the accessibility service binds a little after boot completes
        dev.shell("input keyevent HOME")
        time.sleep(6)
        bound = "label=Home" in dev.shell("dumpsys accessibility")
        focus = dev.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
        ours = PKG + "/" in focus
        print(f"Home-key service bound: {bound} | launcher in front: {ours}")
        if ours and "mCurrentFocus=Window{" in focus and " android}" in focus:
            # Fire OS often shows "Unable to Detect Your Remote" after a reboot; it is not ours.
            print("A Fire OS dialog is over the launcher (usually the remote check); OK dismisses it.")
        return bound and ours
    finally:
        dev.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("apk")
    ap.add_argument("--host", required=True, help="TV IP, e.g. 192.168.1.251")
    ap.add_argument("--no-reboot", action="store_true", help="leave the TV running (Home stays on Amazon until reboot)")
    args = ap.parse_args()

    tv = load_tv(args.host)
    local_md5 = hashlib.md5(open(args.apk, "rb").read()).hexdigest()
    dev = tv.connect()
    try:
        shell(dev, "getprop ro.product.model")
        tv.push_b64(dev, args.apk, "/data/local/tmp/tvhome.apk")
        shell(dev, "pm install -r /data/local/tmp/tvhome.apk", timeout=300)
        shell(dev, "rm -f /data/local/tmp/tvhome.apk")

        remote = dev.shell(f"md5sum $(pm path {PKG} | sed 's/package://' | tr -d '\\r')").split()
        print(f"installed md5 {remote[0] if remote else '?'} | local {local_md5} | "
              f"match={bool(remote) and remote[0] == local_md5}")

        for grant in ("android.permission.WRITE_SECURE_SETTINGS", "android.permission.ACCESS_FINE_LOCATION"):
            dev.shell(f"pm grant {PKG} {grant}")   # errors are fine for a permission already granted
        dev.shell(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")

        # Keep anything else that is enabled: the list is pkg/cls:pkg/cls.
        cur = dev.shell("settings get secure enabled_accessibility_services").strip()
        cur = "" if cur in ("", "null") else cur
        if SERVICE not in cur and f"{PKG}/.HomeKeyService" not in cur:
            cur = f"{cur}:{SERVICE}" if cur else SERVICE
            shell(dev, f"settings put secure enabled_accessibility_services {cur}")
        shell(dev, "settings put secure accessibility_enabled 1")
        shell(dev, f"settings put secure screensaver_components {PKG}/.ClockDream")
        dev.shell(f"cmd package set-home-activity {PKG}/.MainActivity")   # reports success even when Fire OS ignores it
        shell(dev, f"am start -n {PKG}/.MainActivity")

        if args.no_reboot:
            print("\nInstalled. Reboot before relying on the Home key: it goes to Amazon until then.")
            return
        print("\nRebooting so the Home-key service re-attaches...")
        try:
            dev.shell("reboot", timeout=10)
        except Exception:
            pass   # the connection dies with the TV; that is the reboot
        time.sleep(5)
    finally:
        try:
            dev.close()
        except Exception:
            pass
    print("Waiting for it to come back...")
    return 0 if wait_home(tv) else 1


if __name__ == "__main__":
    sys.exit(main())
