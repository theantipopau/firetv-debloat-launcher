# 1.5.5 plan

Status: proposed, 9 November 2026. Follows [1.5.4](../CHANGELOG.md): idle mode, apps opening out of their
tile, and navigation sounds.

Same rules as every release so far:

- Build it on the Stick and check it there.
- Then install it on the Cube and the Samsung.
- Then open the PR and publish the release.

Anything that needs Home Assistant goes to the HA repo first: commit, `./ha-push.sh` (config check must
pass), and restart only with approval.

## At a glance

| # | Item | Where | Benefit | Size | Risk |
|---|------|-------|---------|------|------|
| 0 | Finish 1.5.3/1.5.4 checks | HA, TVs | Doorbell and screensaver confirmed in real use | XS | None |
| 1 | Continue Watching from the apps themselves | Fire TV | Netflix, YouTube, Prime resume tiles with no HA | M | Medium: Fire OS may not supply the data |
| 2 | Now-playing bar | Fire TV | Back to Home mid-show and see what is playing, with play/pause | M | Low |
| 3 | Doorbell card you can act on | Fire TV, HA | See the caller live, or dismiss, from the sofa | M | Medium: the card must not steal the remote by accident |
| 4 | 5-day forecast | HA, Fire TV, Samsung | The week's weather in the detail sheet | S | Low |
| 5 | Samsung catches up | Samsung | Same idle mode, gallery and energy row as the Fire TV | M | Low |
| 6 | Sign-in alert | HA | Know the day an integration (Nest) drops, not when the doorbell fails | S | None |
| 7 | Stick shows "off", not "unavailable" | HA | The offline list stops crying wolf every night | XS | None |

Suggested order: 0, 6, 7 (quick wins and safety), then 4, 2, 5, then 1 and 3. Items 1 and 3 have
open questions to settle first; they can move to 1.5.6 without holding up the rest.

---

## 0. Finish what 1.5.3 and 1.5.4 left open

These were never confirmed in real use.

- **Nest re-sign-in.** The doorbell, its camera and chime have been unavailable since 7 November,
  08:57. Fix it in Settings → Devices & services → Nest. This is a Google sign-in, so you do it.
- **One real doorbell press.**
  - Check the card shows a fresh camera frame on both TVs.
  - Check `www/tv_doorbell.jpg` is gone 30 seconds later.
  - If Nest only gives a grey placeholder instead of a frame, drop the `image` line from
    `tv_doorbell_alert`. A placeholder is worse than no picture.
- **Screensaver painting change.**
  - Leave the screensaver running for over 10 minutes and check the next painting fades in.
  - Check the log shows no recycled-bitmap error.

## 1. Continue Watching from the apps themselves

**What:** a resume row filled by Netflix, YouTube, Prime Video and others through Android's own
"Watch Next" list (`TvContract.WatchNextPrograms`). Each tile shows the app's still, a progress bar and
the episode, and opens straight to where you left off. Plex keeps coming through HA as it does now.

**Why:** today only Plex and the TiviMate guide reach the home screen, and they need HA. This would
be the biggest gain in what the home screen knows, and it works with no server at all.

**Open question, to settle first (about an hour, on the Cube):**

- Fire OS 7 may not let a third-party launcher read other apps' Watch Next entries. That permission
  is often reserved for system apps.
- Amazon's own apps may not write to the list at all.
- **Spike:** declare `com.android.providers.tv.permission.READ_EPG_DATA`, grant it over ADB, query the
  provider, and log what comes back after watching something in Netflix and YouTube.
- **Go / no-go:** at least two of the dock apps write usable rows. If not, drop the item and note
  why in the README.

**How (if go):**

- **Code:** a `WatchNext` reader alongside `Feed`, merged into the existing hidden Continue
  Watching row. That row already has its look, its focus rules and Down-from-the-dock.
- **Ordering:** the newest entry first across all sources.
- **Duplicates:** an app's own entry wins over the HA one for the same title.

**Checks:**

- Rows appear after watching in each app.
- OK resumes at the right spot.
- Nothing breaks when the provider returns no rows.

## 2. Now-playing bar

**What:** when you press Home while something is playing, a slim glass bar sits above the dock:

- app icon
- title
- progress line
- play/pause

OK toggles play/pause; Down falls through to the dock as before. It disappears when playback stops.

**Why:** tvOS does this, and right now going Home mid-show loses all sign of what you were watching.

**How:**

- **Reading the playing app:** `MediaSessionManager.getActiveSessions()`. This needs the launcher to
  be a notification listener.
  - Add a small `NotificationListenerService`.
  - Enable it once with `adb shell cmd notification allow_listener com.mihir.tvhome/.MediaListener`.
  - `install-tv.py` can run that line next to the accessibility one.
- **Updates:** session callbacks only, no polling. The bar updates when the app reports a change.
- **Graceful fallback:** an app that has no media session (some IPTV players) simply shows no bar.

**Checks:**

- **Apps:** Plex, Netflix, YouTube and TiviMate.
- **Remote:** play/pause from the bar works.
- **Lifecycle:** the bar leaves when playback ends, and frame timing on the dock is unchanged
  (`dumpsys gfxinfo`).

## 3. Doorbell card you can act on

**What:** the doorbell card gains two choices for its 10 seconds:

- **Show camera** opens a full-screen live view.
- **Dismiss** closes the card.

Other cards (bins, timers, motion) stay as they are: no buttons, and they never take the remote.

**Why:** the picture added in 1.5.3 shows who rang. The next step is to see them live without
reaching for a phone.

**The catch:** today every card is drawn with `FLAG_NOT_FOCUSABLE`, deliberately, so a card can never
grab the remote from whatever is playing. A card with buttons has to take focus.

**Rules:**

- Only `kind: doorbell` cards ever take focus.
- They give it back the moment they close.
- Back always dismisses.
- If nobody presses anything, the card times out as now and the playing app gets its keys back
  untouched.

**Live view:** this is the second open question.

- Nest gives no plain still or MJPEG stream; HA shows it as a WebRTC stream.
- **Simplest:** a full-screen WebView of the HA camera card with a short-lived access token, the
  same one HA's own web page uses. It is limited to the local network and expires within minutes.
- **Spike first:** confirm the WebView on Fire OS 7 plays HA's WebRTC stream. If it does not, ship
  Dismiss only and keep the picture.

**HA side:** `script.tv_alert` passes an extra `camera` field, which only doorbell alerts send.

**Checks:**

- **Remote:** a card shown during Netflix playback does not pause or steer Netflix unless OK is pressed.
- **Back:** always closes the card.
- **Two TVs:** each TV acts on its own card.

## 4. 5-day forecast

**What:** a row of five days in the HA detail sheet: day name, icon, high and low. It is a compact
line rather than tiles, as the sheet's height rule in `docs/HOME-ASSISTANT.md` asks. The Samsung's
detail sheet gets the same row.

**Why:** the sheet already shows today's weather in detail; the week is the next question people ask.

**How:**

- **HA:** the snapshot has no forecast yet (its keys today run from `ac` to `wind_unit`). Add
  `forecast`, a list of five `{day, cond, hi, lo}`.
  - Fill it from `weather.get_forecasts` (daily) in the template that writes `tvhome.json`.
  - Refresh it hourly; it does not need the per-minute cadence.
- **Fire TV:** a line in the sheet, reusing the existing weather icons.
- **Samsung:** a line in the sheet in `dashboard.js`.
- **Missing data:** a missing or short list hides the row. There is never a half row.

**Checks:**

- The days match the HA weather card.
- The sheet still fits 540dp.
- An empty `forecast` hides the row on both TVs.

## 5. Samsung catches up

**What:** the Tizen dashboard gets what the Fire TV gained in 1.5.3 and 1.5.4:

- **Idle mode:** after a minute untouched, the showing card fills the screen. Any key returns.
- **Gallery:** after a few minutes more, the paintings with their fading caption, a big clock, the
  weather and the energy row (battery, solar, house load) under it.
- **"Details ›" hint** while the status block has focus.
- **Navigation sounds:** left out. The Samsung's own UI already clicks, and a second sound would clash.

**Why:**

- The two screens should feel like one product.
- The Samsung currently shows the same still dashboard all night. A drifting, changing picture is
  also kinder to the panel.

**How:**

- **Code:** all in `tizen/dashboard.js` and `style.css`.
- **Paintings:** copied into `tizen/` at packaging time by `tools/package-tizen.py`, from
  `launcher/assets/art/`, so there is one source.
- **Chromium 69 limits** still apply: no `inset`, no `backdrop-filter`. Movement uses stepped
  transforms, as the wallpaper drift already does.

**Checks:**

- Idle mode and the gallery appear at the right times on the TV.
- The first key press only wakes the screen.
- The package size stays sensible (four paintings, about 1.5 MB).

## 6. Sign-in alert

**What:** an HA automation that notifies your phone when any integration fails to load or asks to
sign in again. It names the integration and links to Devices & services.

**Why:** Nest dropped on 7 November and nothing said so. The doorbell, its TV card and its phone alert
all went quiet for over a day.

**How:**

- **Trigger:** watch for `config_entry` failures through HA's repairs/persistent notifications for
  re-authentication.
- **Fallback:** a 15-minute template check of config entry states (`setup_error`, `setup_retry`,
  `not_loaded`).
- **Repeats:** one notification per integration per day, so a flapping one does not spam.
- **Where:** the HA repo only.

**Checks:**

- Disabling and re-enabling a harmless integration fires exactly one notification.
- Nest in its current state fires one now.

## 7. Stick shows "off", not "unavailable"

**What:** the Stick's media player reads "off" when the Stick is asleep, rather than "unavailable".

**Why:** today it goes unavailable every night, which looks like a fault.
`sensor.devices_offline` and anything else watching availability treat a sleeping Stick like a
broken one.

**How:**

- **HA:** a template media player, or an availability template, that maps "unavailable while the
  Samsung is off" to off.
- **Canary list:** exclude the Stick from `sensor.devices_offline` while the TV it plugs into is off.
- **Where:** the HA repo only.

**Checks:**

- Overnight the Stick reads off, not unavailable.
- The offline sensor stays at 0 with only sleeping TVs.

---

## Not in 1.5.5

- **New hardware.** Vega OS Fire TVs cannot run this launcher. The Cube and the 4K Max are the right
  devices to keep.
- **Replacing the Home-button method.** The open-source Home on Fire project uses the same
  accessibility approach, with a plainer cover. Nothing better exists on Fire OS 7.
- **Ken Burns movement on idle mode.** Continuous motion all evening costs GPU and power. The 10-second
  crossfades already give it life.

## Release checklist

1. Changelog entry and `versionName` 1.5.5 / `versionCode` 16.
2. `bash tests/run.sh` passes, with new tests for any new pure logic: forecast parsing, and the
   Watch Next merge order.
3. Stick: install, reboot, Home bound; check each item above.
4. Cube and Samsung: install; check Home on the Cube and the dashboard on the Samsung.
5. PR, merge, generic APK built from `main` with an empty config, GitHub release.
