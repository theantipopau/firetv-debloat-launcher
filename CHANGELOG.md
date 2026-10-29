# Changelog

All notable changes to the Fire TV Debloat Launcher. Versions match `versionName` in
[`launcher/AndroidManifest.xml`](launcher/AndroidManifest.xml).

## 1.3.2 — 2026-10-29

### Fixed
- **The Home-key cover is now the screen behind it.** Both are built from one `HomeTheme`: the same top bar and
  clock formatter, the real dock icons from `PackageManager`, the last hero backdrop cached as a downscaled JPEG,
  and Continue Watching as skeletons (or hidden when the last list was empty). The old "Opening Home…" line, the
  54sp clock, the letter-spaced greeting and the emoji panel are gone; a 24dp spinner appears bottom-right after
  1.5s, and the cover fades out as soon as the launcher draws.
- **Continue Watching reads as Plex.** Each tile carries Plex's installed icon top-left, the episode still (then the
  show backdrop, never a cropped poster), a bottom gradient, and a 4dp progress bar inset inside the tile so the
  focus rim can never paint over it.
- **The top bar holds up over bright artwork.** A 180dp scrim under it, 24dp between glance items with a thin
  separator, 32dp before the clock, "Bins today/tomorrow" in the accent colour only when it is due, and the same
  15-minute staleness dimming the panel uses (now measured from `last_updated`, so a battery that only moves at the
  odd percent is not dimmed for being quiet).
- **The Home Assistant sheet fits its content** instead of leaving half of it empty, hides the top bar while open,
  and reads the bins tile as "Tomorrow" over "General bins".
- **Long hero titles shrink to fit.** 48sp down to 36sp before wrapping, two lines maximum, then an ellipsis; the
  live meta line picks up an EPG subtitle when the guide has one.

### Added (testing)
- `tests/run.sh` now also covers the title auto-size rules and the Continue Watching artwork order plus the
  glance strip's staleness dimming (86 checks).

## 1.3 — 2026-10-29

### Added
- **Live now in the rotating tile.** `tvhome_keep.py` reads the XMLTV guides named by `epg_urls` in
  `secrets.yaml` (or `EPG_URLS`), and puts what is on right now in front of the new-on-Plex cards: live sport
  first — cricket, rugby, football/AFL, racing and the US leagues all match — then one film when one is airing.
  Each card carries a red-dot **LIVE ON TIVIMATE** chip and a "channel · until 21:35" caption, and opens TiviMate.
  With no programme still in the guide, the channel logo fills the card, contained instead of cropped.
  Simulcasts collapse to a single card; replays, studio shows, 24/7 filler channels and empty "Stan Sport
  Event 12" placeholders are dropped. Every card knows when it ends, so the TV stops offering a programme once
  it has finished instead of waiting for the next refresh.
- The tile now reads "live now, then new on Plex". With no guide configured, or nothing live, it behaves exactly
  as it did in 1.2.
- Guides are cached in `/config/.tvhome_epg/` and downloaded again only when the server reports a change — the
  iptv4u guide on its own is 11 MB, which is worth not fetching every ten minutes.

### Fixed
- **A reboot landed on Amazon's home.** Fire OS launches its own launcher at boot even when another home is
  installed and selected, so the app now takes the screen back nine seconds after boot, and checks once more at
  26 seconds in case Amazon came back after it. `tools/firetv-install.sh` also selects it as the home activity
  (`cmd package set-home-activity`), so the system has a preferred home as well as the accessibility service.

### Changed
- **Inter, in four static weights** (Light, Regular, Medium, SemiBold; SIL OFL, licence in `LICENSES/`). The whole
  screen now uses one type scale: clock 96sp Light, temperature 72sp Light, headings 28sp SemiBold, body 22sp,
  captions 18sp, nothing smaller. The clock, temperature and every figure use tabular numerals, so nothing shifts
  as digits change. Small-caps labels use about half the old letter-spacing.
- **The Home Assistant panel is a 3-column tile grid** instead of stacked coloured lines: solar, battery and house
  load; inside, AC and bins. Monochrome line icons (Material Symbols Rounded), white values, 60% white labels, and
  one accent colour used only for alerts. kW values are rounded to one decimal. Rain collapses to "No rain
  expected" when there is none. Calendar text loses its bracketed codes and gains relative time ("· in 2 h"). A
  value whose entity has not changed for 15 minutes dims to 40% and shows how old it is.
- **Frosted glass instead of a flat black card.** The panel samples a pre-blurred copy of the current wallpaper at
  its own position, with a 12% hairline and a 24dp radius, and the dock sits on its own gradient. RenderEffect needs
  API 31 and this build targets 28 (Fire OS 7), so the blur is pre-computed off the main thread; until it exists the
  panel falls back to 55% black.
- **One hero banner replaces the tile row.** Live programmes, continue-watching and new-on-Plex now share a single
  wide banner above the dock, one at a time, with a source chip, the title or its clearlogo, one meta line and dots.
  It crossfades in 600 ms, drifts slowly inwards, changes every nine seconds, holds still while focused, and steps
  with Left/Right. Eight frames at most.
- **Dock focus is light, not a border.** The focused tile scales to 1.08, lifts 4dp with a soft shadow, and takes one
  sheen sweep in 170 ms; the heavy focus ring and its dark variant for pale icons are gone. Tiles share a 14dp radius.
- The app-name heading that used to float above the dock is gone; the banner carries the title instead.
- Wi-Fi reports `Link 720 Mbps` (or just the network name when the driver will not say), so it cannot be mistaken
  for the broadband speed. Guide end times are formatted on the TV in its own zone and 12/24-hour setting, and use
  the same format as the clock.
- Artwork is reused between runs, and a single failed image is retried on the next run without re-downloading
  the ones that worked.

### Added (testing)
- `tests/run.sh` runs 23 plain-JVM checks over the rules that are easy to get silently wrong: guide times converted
  from a UK and a New Zealand feed into the TV's zone, 12/24-hour formatting, calendar cleaning, relative times, kW
  rounding and the staleness threshold. No Gradle, no JUnit: the launcher has neither.

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
