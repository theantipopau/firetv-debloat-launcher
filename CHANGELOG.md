# Changelog

All notable changes to the Fire TV Debloat Launcher. Versions match `versionName` in
[`launcher/AndroidManifest.xml`](launcher/AndroidManifest.xml).

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
