# Changelog

All notable changes to the Fire TV Debloat Launcher. Versions match `versionName` in
[`launcher/AndroidManifest.xml`](launcher/AndroidManifest.xml).

## 1.2 — 2026-10-28

### Added
- **A rotating "new on Plex" banner.** With `new_json_url` set, the keep-watching row opens with a two-slot wide card —
  hairline frame, larger logo, "NEW ON PLEX" chip — that crossfades through the newest movies and episodes in your Plex
  libraries every seven seconds. It holds still while focused, so OK always opens what is on screen, and titles the row
  is already showing are skipped rather than duplicated next to themselves.
- `tvhome_keep.py` now writes `tvhome_new.json` on the same run: five recent film releases (this year or last) plus
  five episodes that aired in the past 60 days, one card per series. Old titles being imported or backfilled are
  filtered out by release/air date, so the tile shows what is actually new rather than what Plex scanned last.
  `PLEX_NEW_MOVIES`, `PLEX_NEW_EPISODES`, `PLEX_NEW_DAYS` and `PLEX_NEW_LIBS` tune it. Artwork only downloads again
  when the list changes, so a quiet library costs a couple of API calls per run.

### Fixed
- `tvhome_keep.py` warns when `plex_url` or `ha_base_url` is missing from `secrets.yaml` instead of quietly falling
  back to `localhost:32400` / `homeassistant.local:8123` — the second one produced cards with titles but no artwork,
  because Fire OS could not resolve that name for the images.
- The two feeds race at startup; if the resume cards arrived first they kept their full count and slid under the
  weather panel once the banner appeared. The row is now trimmed to one resume card when the banner is present.

### Changed
- The keep row shows one resume card beside the banner: a two-slot card plus one 16:9 card is what fits in front of the
  weather panel. Without `new_json_url` the row is unchanged (up to five resume cards).

## 1.1.1 — 2026-10-28

### Fixed
- **Home went to Amazon's launcher after an update.** Android turns a package's accessibility services off when that
  package is updated, and Fire OS only attaches them again at boot, so the first Home press after an update landed on
  Amazon instead of the cover. `tools/firetv-install.sh` now reboots the TV when it finishes, the app re-arms the
  service from `MY_PACKAGE_REPLACED` where the system honours it, and the install docs say to reboot after a manual
  `adb install -r`. Both TVs were restored and verified after this change.

## 1.1 — 2026-10-28

### Added
- **The Home key now lands on a finished screen.** The cover the Home-key service paints while the launcher
  starts used to be a plain backdrop; it now reproduces the home screen — wallpaper, greeting and clock, weather
  panel, top-right pills and the dock band — so pressing Home no longer flashes an empty screen on the way in.
  The wallpaper is decoded ahead of time, so the press costs no hitch.
- Show logos inside the keep-watching cards. Plex and Netflix hand over a transparent PNG for the title, which is
  now what the card shows; our own text only stands in when there is none.
- A progress track under each card, inset from the rounded corners.
- Twelve new wallpapers: nature and landscape photography plus public-domain paintings (Turner, Whistler,
  Constable, Van Gogh). Credits for every file are in
  [`launcher/WALLPAPER-CREDITS.md`](launcher/WALLPAPER-CREDITS.md).
- This changelog.

### Changed
- **Keep-watching row rebuilt.** The old card was 200×62 dp — a 3.2:1 window onto 16:9 artwork, with the label, the
  show logo and the episode line stacked in the same 62 dp. It is now a 16:9 still with the logo inside it and the
  episode line underneath. This release also slims the card to 156 dp so the row reads as secondary to the app
  title.
- **The Inputs pill only appears where it can work.** It is shown when the device reports a real HDMI passthrough
  input, excluding ARC and Amazon's own inputs: the Cube has one, the Stick has nothing to switch to, so no pill.
- The dock tray and the info panel sit above the photo with elevation, and the app title fades instead of swapping
  when the name changes.
- All network traffic goes through one helper that enforces http(s), a byte cap, a decode-pixel cap and timeouts, so
  a bad URL in the Home Assistant JSON costs a card, not the launcher.
- Alert cards are gated behind `android.permission.DUMP`: only the TV's shell (ADB, which is what Home Assistant
  uses) and system apps can trigger one, so an installed app cannot spoof a doorbell. Payloads are length-capped,
  rate-limited, and brand images come from an allowlist.

### Fixed
- **Uninstall did nothing.** Holding OK → Uninstall closed the menu and left you where you were: Fire OS accepts the
  `ACTION_DELETE` intent from a third-party caller and then drops it, so the installer never appears. Uninstall now
  opens the app's own Settings page, where the Uninstall row works.
- The Home-key service no longer rewrites the accessibility list with our service alone; every other enabled service
  is preserved.
- Keep-watching captions had a drop shadow wider than the type itself, which smeared them into a double image.
- The keep-watching row no longer overflows: it is limited to the five cards that fit.
- Dock ↔ keep-watching focus is routed explicitly. The framework's focus search kept falling back to the top bar
  even with the next-focus ids set, and the ends of the row now hold instead of jumping rows.
- Uploads to the TV verify size and md5 before installing, so a truncated push can no longer leave the previous
  build installed while reporting success.

### Removed
- The eight NASA wallpapers; the slideshow is nature and art only now.

## 1.0

Initial public release.
