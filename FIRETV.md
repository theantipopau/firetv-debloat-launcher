# tv-home for Fire TV (Fire TV Stick 4K Max, Fire TV Cube, Fire OS 7, Android 9)

A fast, ad-free, Apple TV style home screen for Fire TV, forked from
[mihirgupta0900/tv-home](https://github.com/mihirgupta0900/tv-home) (MIT). Home Assistant is **optional**.
Not affiliated with Amazon. Everything here is reversible; nothing is uninstalled.

## What you get
- Clean home: greeting, clock, wallpaper slideshow (slow zoom, time-of-day tint), app dock plus an apps list.
- **Home button works, and a reboot comes back here.** Fire OS starts its own launcher at boot even when this
  one is selected as home, so the app takes the screen back a few seconds after boot. An accessibility service
  catches the Home key; Android holds other apps back for about 5 seconds after Home is pressed, so a cover screen
  (greeting, clock, loading bar) is shown meanwhile and the launcher fades in.
- **Hold OK on an app**: move left/right, add to / remove from the main row, hide, app info, uninstall.
  Long-press the Settings pill to reset the row and bring back hidden apps. Recently opened apps float to the front of the list.
- **Settings and Wi-Fi pills** open Amazon's own full-screen pages. Wi-Fi/Ethernet pill with icon and link speed.
- **Inputs pill** (Fire TV Cube / TV-edition sets with HDMI in) and a **Voice** pill (same as the remote's mic button).
- **Screensaver**: big clock over a drifting wallpaper after 3 minutes idle on Home (also registered as the system screensaver).
- **Alert cards** over any app (`am broadcast ...`), for doorbell, bins, timers. Optional.
- With **Home Assistant** (optional): info panel (weather, solar, battery, house power, bins, next event),
  "keep watching" cards from Plex, alerts, sleep timer. See [docs/HOME-ASSISTANT.md](docs/HOME-ASSISTANT.md).

## Install (about 10 minutes)
1. On the Fire TV: Settings > My Fire TV > About, press OK on the device name 7 times. Then Developer Options:
   turn on **ADB debugging** (and **Apps from unknown sources** if you sideload another way).
2. On your PC install `adb` ([platform-tools](https://developer.android.com/tools/releases/platform-tools)).
3. Build the APK (`python launcher/build_win.py`; see "Building") or use a release APK.
4. Run:
   ```
   tools/firetv-install.sh <tv-ip> launcher/out/tvhome.apk
   ```
   Accept the "Allow USB debugging" prompt on the TV the first time. The script installs, grants the
   permissions the launcher needs, selects it as the home activity, switches on the Home-button service and
   reboots the TV.
5. If you install an updated APK another way (`adb install -r`), reboot the TV afterwards: Android turns an app's
   accessibility services off when the app is updated, and Fire OS only attaches them again at boot. Without the
   reboot the next Home press goes to Amazon's launcher.
6. Optional clean-up and speed-ups (reversible): `tools/firetv-tune.sh <tv-ip>`.

## Make it yours
Edit [`launcher/res/values/config.xml`](launcher/res/values/config.xml) (or, to keep personal settings out of git, copy it to
`launcher/profiles/<name>/config.xml` and build with `python launcher/build_win.py <name>`):

| Setting | What it does |
|---|---|
| `family_name` | "Good evening, **Smith Family**". Empty = just "Good evening". |
| `device_name` | Shown after the date, e.g. "Living room". Empty = hidden. |
| `dock_apps` | Main row order (package names). Find them with `adb shell pm list packages -3`. |
| `hidden_apps` | Never shown. |
| `ha_json_url`, `keep_json_url` | Home Assistant files. Empty = no panel / no keep-watching row. |
| `columns` | Tiles per row in the apps list. |

**Wallpapers**: drop any 1920x1080 (or larger) `.jpg` into `launcher/assets/wallpapers/` and rebuild.
`python tools/fetch_wallpapers.py --nasa 10` downloads NASA public-domain space pictures and records credits in
`launcher/WALLPAPER-CREDITS.md`. Delete pictures you do not want first; every picture adds to the APK size.

**Rename the TV on screen**: set `device_name`, rebuild, reinstall (settings are compiled in, so the launcher needs no account).

**On-TV changes** (no rebuild): hold OK on an app to move or hide it.

## Building
Windows: `setup.sh`-style toolchain is not needed; `launcher/build_win.py` expects `toolchain/` with a JDK 17 and the Android SDK
(platform 28, build-tools 34) next to the repo. No Gradle. Output: `launcher/out/tvhome.apk` (or `tvhome-<profile>.apk`).
Linux/macOS: `launcher/build-and-install.sh` (upstream script).

## Remove it / go back to Amazon's home
```
tools/firetv-undo.sh <tv-ip>
```
This turns the Home service off, re-enables anything `firetv-tune.sh` disabled and uninstalls the launcher. Manual version:
```
adb shell settings put secure enabled_accessibility_services null
adb shell am force-stop com.mihir.tvhome
adb shell pm uninstall --user 0 com.mihir.tvhome
```

## Fire OS facts worth knowing
- `pm disable-user com.amazon.tv.launcher` is refused ("protected package"), so Amazon's home still exists behind ours.
  `cmd package set-home-activity` reports success but changes nothing.
- A real remote's Home button never reaches an accessibility service's key filter. The launcher reacts to Amazon's home window
  opening instead, and the cover hides the wait. (ADB `input keyevent HOME` behaves differently from the remote.)
- Amazon Settings pages open on top of ours and Back returns to the launcher.
- The Home service can lose its binding after a force-stop or reinstall; the launcher re-enables it on start
  (needs `WRITE_SECURE_SETTINGS`, granted over ADB by the install script).
- Amazon's home-screen ads are loaded from Amazon's servers. The launcher hides them; to remove them at the network, block the
  hosts listed in [docs/DEBLOAT.md](docs/DEBLOAT.md) in Pi-hole / AdGuard Home / your router.

## Credits and licence
MIT. Original launcher by Mihir Gupta. Fire TV port, Home-key service, cover screen, alerts, screensaver and Home Assistant
integration added here. App icons come from each app's own banner on your device. Wallpaper credits:
[`launcher/WALLPAPER-CREDITS.md`](launcher/WALLPAPER-CREDITS.md).
