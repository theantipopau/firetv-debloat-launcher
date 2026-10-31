<p align="center"><img src="docs/img/logo.jpg" alt="Fire TV Debloat Launcher" width="320"></p>

# Fire TV Debloat Launcher

A clean, fast, ad-free home screen for **Amazon Fire TV**, plus a reversible debloat script. No ads, no sponsored rows, no
account, no cloud. Works on its own; [Home Assistant](docs/HOME-ASSISTANT.md) is optional.

Current release: **v1.5.1** — see the [changelog](CHANGELOG.md).

![Home screen](docs/img/home.jpg)

| Alert cards over any app | While Home is opening | Screensaver |
|---|---|---|
| ![Alert](docs/img/alert.jpg) | ![Opening Home](docs/img/opening-home.jpg) | ![Screensaver](docs/img/screensaver.jpg) |

## Tested on
- **Fire TV Cube (3rd gen, AFTGAZL)**, Fire OS 7.7.1.7 (Android 9), wired through a USB Ethernet adapter
- **Fire TV Stick 4K Max (AFTKA)**, Fire OS 7.7.1.3 (Android 9)

Other Fire OS 7 devices will probably work. Not tested on Fire OS 8 / Vega OS devices.

## Features
- Apple TV style home: a greeting, the clock and a few plain lines at a glance over a slow-zoom wallpaper slideshow,
  one glass dock of your main apps, and every other app behind **All apps**. The clock follows the TV's own
  12/24-hour setting.
- **Live now, then new on Plex.** Two rotating cards above the dock: what is on TiviMate right now, read from your
  XMLTV guide (the channel's own logo and when it ends), and the newest films and episodes on Plex. They change half
  a beat apart, so the two never turn together, hold still while focused, and OK opens the one on screen. Ten of
  each take a turn.
- **Home button takes you here.** Fire OS blocks other apps for a few seconds after Home is pressed, so a cover screen
  (the same wallpaper, clock and dock, with skeleton lines) hides Amazon's home until the launcher opens.
- Hold OK on any app to move it, add it to the main row, hide it, open its info or uninstall it. No rebuild needed.
- Settings and the network pill open Amazon's own full-screen pages. The pill is an icon at rest and shows the
  network's name and link rate on focus (Wi-Fi 720 Mbps, Ethernet 1 Gbps on a gigabit adapter). **Inputs** sits in
  Settings on devices that report an HDMI passthrough input.
- Screensaver (big clock over a drifting wallpaper) instead of Amazon's ad-supported one.
- Alert cards over whatever is playing: doorbell, bins, timers (`am broadcast`, or from Home Assistant). Only shell/ADB
  senders can trigger one, so an app installed on the TV cannot spoof a card.
- **Debloat script** (reversible): disables Amazon shopping, ad-ID and content-recognition packages, limits ad tracking, speeds up animations.
- Optional Home Assistant link: the frosted block top-left shows the weather and inside temperature, the battery
  (with the roof's solar output beside it while producing), air con and bins; OK opens its detail sheet (solar,
  house load, sunrise/sunset, who is home). Down from the dock reveals **Continue Watching**, and the package adds a
  sleep timer and a doorbell card.
  Plus [prompts for Claude, Codex or any AI agent](docs/AI-PROMPTS.md) that do the setup for you.

## Quick start
1. On the TV: Settings > My Fire TV > About, press OK on the device name 7 times, then Developer Options > turn on **ADB debugging**.
2. Build the APK — no Gradle, just JDK 17 and the Android SDK with build-tools 34 (on Windows `build_win.py` reads
   them from `toolchain/`; see [FIRETV.md](FIRETV.md)). `python launcher/build_win.py` writes
   `launcher/out/tvhome.apk`; `launcher/build-and-install.sh --build` on macOS/Linux writes `launcher/build/tvhome.apk`.
3. ```
   tools/firetv-install.sh <tv-ip> launcher/out/tvhome.apk             # needs adb on your PC
   python tools/install-tv.py launcher/out/tvhome.apk --host <tv-ip>   # no adb binary needed
   tools/firetv-tune.sh <tv-ip>                                        # optional: ad/bloat clean-up
   ```
   Accept the on-TV debugging prompt the first time. Either installer grants the permissions the launcher needs,
   selects it as the home activity, switches on the Home-key service and reboots the TV — Fire OS only re-attaches
   that service at boot, so the reboot is part of the install. `tools/install-tv.py` needs
   `pip install -r tools/requirements.txt`.
4. Press Home on the remote. Undo everything any time with `tools/firetv-undo.sh <tv-ip>`.

Full guide, settings (name, family name, app order, wallpapers) and Fire OS notes: [FIRETV.md](FIRETV.md).
Ad hosts to block in Pi-hole / AdGuard: [docs/DEBLOAT.md](docs/DEBLOAT.md).

## Limits (the honest list)
- Amazon's own home screen **cannot be removed** (it is a protected package); this puts a launcher in front of it and hides the ads.
  Banners on Amazon's screen come from Amazon's servers, so block those hosts at the network if you want them gone entirely.
- The remote's Home press reaches Amazon first, so there is a short cover screen (about 4 seconds) before the launcher appears.
- Netflix does not share watch history, so Continue Watching comes from Plex only (a manual Netflix card is possible).
- The mic button is handled by Amazon and still works; there is no voice button in the launcher.
- This is an unofficial project, not affiliated with or endorsed by Amazon. You use it at your own risk; everything is reversible.

## Credits and licence
MIT. Forked from [mihirgupta0900/tv-home](https://github.com/mihirgupta0900/tv-home) (Android TV launcher, original README in
[docs/UPSTREAM-README.md](docs/UPSTREAM-README.md)). Fire TV port, Home-key service, cover screen, alerts, screensaver, debloat tools and
Home Assistant integration added here. App tiles use each app's own banner from your device. Typeface: Inter, SIL Open Font
Licence in [LICENSES/Inter-OFL.txt](LICENSES/Inter-OFL.txt). Wallpapers: see
[`launcher/WALLPAPER-CREDITS.md`](launcher/WALLPAPER-CREDITS.md) (Wikimedia Commons: nature photos CC BY-SA, paintings public domain).
